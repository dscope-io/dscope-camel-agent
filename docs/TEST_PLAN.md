# Test Plan

## Unit

1. `MarkdownBlueprintLoaderTest`
   - parses valid `agent.md`
2. `SchemaValidatorTest`
   - rejects missing required fields
3. `DefaultAgentKernelTest`
   - produces assistant message + lifecycle events
   - async task waiting/resume transitions
   - dynamic route lifecycle persistence in memory
4. `DscopePersistenceFacadeTest`
   - appends/reads conversation events
   - saves/rehydrates task snapshot
   - saves/rehydrates dynamic route snapshot
   - distributed task lock claim/release/lease-expiry behavior
5. `SpringAiModelClientTest`
   - delegates to chat gateway
6. `SpringAiMessageSerdeTest`
   - serializes/deserializes Spring AI messages including tool calls/responses
7. `DscopeChatMemoryRepositoryTest`
   - save/find/delete conversation memory with DScope persistence contract
8. `AgUiAgentEventPublisherTest`
   - maps tool lifecycle to AGUI bridges
9. `AgentAutoConfigurationTest`
   - starter properties/auto-config basic loading

10. `DefaultAgentKernelTest`
   - lock conflict path when another node owns a task lease
11. `MultiProviderSpringAiChatGatewayTest`
   - OpenAI tool-name normalization for API-compatible function names
   - tool-call name remap back to original tool names for kernel dispatch

## Integration

1. `SpringAiAuditTrailIntegrationTest`
   - two-turn deterministic scenario:
     - prompt 1 selects `kb.search`
     - prompt 2 selects `support.ticket.open`
     - second prompt evaluation includes first-turn KB result
   - negative scenario:
     - ticket as first prompt does not inject KB result context
   - live LLM scenario (when key available):
     - validates real route selection and second-turn context carry-over
2. End-to-end route execution with `agent:` endpoint and `direct:kb-search`
3. `redis_jdbc` fallback behavior with Redis miss and JDBC success
4. Restart rehydration scenario for unfinished task
