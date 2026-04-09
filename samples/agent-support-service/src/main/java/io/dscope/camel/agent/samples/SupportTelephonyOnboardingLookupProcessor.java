package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.telephony.onboarding.TelephonyOnboardingPersistenceService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

final class SupportTelephonyOnboardingLookupProcessor implements Processor {

    private final ObjectMapper objectMapper;

    SupportTelephonyOnboardingLookupProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String tenantId = readHeader(exchange, "tenantId", "default");
        String agentId = readHeader(exchange, "agentId", "default");
        String conversationId = TelephonyOnboardingPersistenceService.conversationId(tenantId, agentId);

        PersistenceFacade persistenceFacade = exchange.getContext().getRegistry().lookupByNameAndType("persistenceFacade", PersistenceFacade.class);
        if (persistenceFacade == null) {
            writeError(exchange, 503, "Persistence facade is not available");
            return;
        }
        TelephonyOnboardingPersistenceService store = new TelephonyOnboardingPersistenceService(persistenceFacade, objectMapper);
        ObjectNode response = store.loadLatest(conversationId).orElse(null);
        if (response == null) {
            writeError(exchange, 404, "No onboarding record found for tenantId=" + tenantId + ", agentId=" + agentId);
            return;
        }

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private String readHeader(Exchange exchange, String headerName, String defaultValue) {
        Object value = exchange.getMessage().getHeader(headerName);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private void writeError(Exchange exchange, int statusCode, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(java.util.Map.of(
            "error", message
        )));
    }
}