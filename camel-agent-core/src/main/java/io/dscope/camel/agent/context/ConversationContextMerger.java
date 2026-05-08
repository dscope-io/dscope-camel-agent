package io.dscope.camel.agent.context;

@FunctionalInterface
public interface ConversationContextMerger<T> {

    T merge(T current, T newer);
}