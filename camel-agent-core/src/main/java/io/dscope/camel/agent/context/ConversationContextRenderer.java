package io.dscope.camel.agent.context;

@FunctionalInterface
public interface ConversationContextRenderer<T> {

    String render(T context);
}