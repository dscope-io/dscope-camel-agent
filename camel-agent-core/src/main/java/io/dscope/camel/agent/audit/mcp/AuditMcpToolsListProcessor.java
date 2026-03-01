package io.dscope.camel.agent.audit.mcp;

import io.dscope.camel.mcp.processor.AbstractMcpResponseProcessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;

public class AuditMcpToolsListProcessor extends AbstractMcpResponseProcessor {

    private final AuditMcpMethodsSupport methodsSupport = new AuditMcpMethodsSupport();

    @Override
    protected void handleResponse(Exchange exchange) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", loadMethods());
        writeResult(exchange, result);
    }

    private List<Map<String, Object>> loadMethods() throws Exception {
        List<Map<String, Object>> loaded = new java.util.ArrayList<>();
        for (Map<String, Object> tool : methodsSupport.loadMethods()) {
            if (tool == null || tool.isEmpty()) {
                continue;
            }
            if (methodsSupport.uiOutputUri(tool) != null) {
                continue;
            }
            loaded.add(tool);
        }
        return loaded;
    }
}
