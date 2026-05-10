---
name: "Camel Maven Testing"
description: "Use when running or choosing Maven commands for Camel agent tests, JUnit slices, sample-service integration tests, module regression checks, or deciding how broad validation should be in this repository."
---
# Camel Maven Testing

- Prefer the narrowest executable validation first: test method, test class, module, sample, then reactor.
- When a failing command is already known, rerun that exact command before broadening scope.
- For module-scoped validation, prefer Maven slices like `mvn -pl camel-agent-core -DskipTests=false -Dtest=ClassName test` over full-reactor builds.
- For sample validation, prefer the specific sample module and test selector, and add `-am` only when required to build dependent modules.
- In debug mode, inspect the generated logs, surefire reports, captured artifacts, and any available screenshots before concluding.
- Every test-oriented response should include a comprehensive report with the command used, scope, scenario, status, timings when available, and the strongest failure or pass signals.
- When screenshots are available in the workspace or shared in chat, include their key observations in the debug report rather than treating them as optional context.
- Preserve repeatability for performance-oriented checks by keeping input scope, module selection, and command options explicit in the report.
- When evaluating performance, distinguish the layer being measured: HTTP endpoint, route or processor, agent orchestration, persistence, external provider call, or UI-driven sample flow.
- Use full-reactor validation only when the user explicitly asks for it or when narrower checks cannot answer the question.
- After the first substantive code edit, rerun the same focused failing check before doing more reading or patching.
- Report the exact command used and whether the result applies to a method, class, module, sample, or reactor scope.