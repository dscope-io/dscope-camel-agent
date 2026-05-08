package io.dscope.camel.agent.context;

@FunctionalInterface
public interface ConversationContextExtractor<T> {

    T extract(String text);
}