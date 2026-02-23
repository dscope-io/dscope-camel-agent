package io.dscope.camel.agent.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.BlueprintLoader;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.api.ToolRegistry;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.executor.CamelToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Endpoint;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

@Component("agent")
public class AgentComponent extends DefaultComponent {

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        AgentEndpoint endpoint = new AgentEndpoint(uri, this);
        endpoint.setAgentId(remaining);
        setProperties(endpoint, parameters);

        BlueprintLoader blueprintLoader = new MarkdownBlueprintLoader();
        AgentBlueprint blueprint = blueprintLoader.load(endpoint.getBlueprint());

        ToolRegistry toolRegistry = findRegistry(ToolRegistry.class)
            .orElseGet(() -> new DefaultToolRegistry(blueprint.tools()));
        ProducerTemplate producerTemplate = getCamelContext().createProducerTemplate();
        ObjectMapper mapper = findRegistry(ObjectMapper.class).orElseGet(ObjectMapper::new);
        PersistenceFacade persistenceFacade = findRegistry(PersistenceFacade.class).orElseGet(InMemoryPersistenceFacade::new);
        ToolExecutor toolExecutor = findRegistry(ToolExecutor.class).orElseGet(() -> new CamelToolExecutor(producerTemplate, mapper));
        AiModelClient aiModelClient = findRegistry(AiModelClient.class).orElseGet(StaticAiModelClient::new);

        AgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            toolRegistry,
            toolExecutor,
            aiModelClient,
            persistenceFacade,
            new SchemaValidator(),
            mapper
        );

        endpoint.setAgentKernel(kernel);
        return endpoint;
    }

    private <T> Optional<T> findRegistry(Class<T> type) {
        return Optional.ofNullable(getCamelContext().getRegistry().findSingleByType(type));
    }
}
