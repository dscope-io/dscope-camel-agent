package io.dscope.camel.agent.model;

import java.util.List;

public record ExceptionPolicySpec(
    String name,
    String scope,
    ExceptionCategory category,
    List<Integer> httpStatusCodes,
    ExceptionAction action,
    RetryPolicySpec retry,
    String prompt
) {
}