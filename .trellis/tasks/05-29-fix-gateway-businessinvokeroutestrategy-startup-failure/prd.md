# fix gateway BusinessInvokeRouteStrategy startup failure

## Goal

Fix ai-gateway startup failure caused by Spring trying to instantiate
`BusinessInvokeRouteStrategy` through a missing no-arg constructor.

## What I Already Know

- Gateway fails during Spring context initialization before Tomcat finishes startup.
- The dependency chain reaches `SkillRelayService` constructor parameter
  `List<InvokeRouteStrategy>`, then fails while creating `BusinessInvokeRouteStrategy`.
- The class has a production constructor that accepts `CloudAgentService` and an
  additional package-private constructor used by tests to inject an executor.
- With multiple constructors and no explicit Spring constructor annotation,
  Spring falls back to no-arg instantiation and reports `No default constructor found`.

## Requirements

- Keep the async business invoke behavior unchanged.
- Keep the package-private executor-injection constructor for focused tests.
- Make Spring select the production `CloudAgentService` constructor at runtime.
- Do not widen the change into route behavior, cloud protocol behavior, or
  unrelated gateway startup dependencies.

## Acceptance Criteria

- [x] `BusinessInvokeRouteStrategy` is created by Spring without requiring a
      no-arg constructor.
- [x] Existing `InvokeRouteStrategyTest` coverage still passes.
- [x] ai-gateway module compiles or the relevant startup test path is verified.
- [x] GitNexus impact and change detection show only the expected narrow scope.

## Definition of Done

- Tests or build command run for the touched ai-gateway path.
- `gitnexus_detect_changes()` confirms expected affected scope.
- Working tree contains only the intended task and code changes.

## Technical Approach

Add an explicit Spring constructor injection annotation to the
`BusinessInvokeRouteStrategy(CloudAgentService)` constructor. This preserves the
existing test-only constructor while removing ambiguity for Spring bean creation.

## Out of Scope

- Changing `SkillRelayService` route strategy collection behavior.
- Changing cloud invoke routing semantics.
- Refactoring executor ownership or MDC propagation.

## Technical Notes

- Relevant spec files read:
  `.trellis/spec/ai-gateway/backend/directory-structure.md`,
  `.trellis/spec/ai-gateway/backend/conventions.md`,
  `.trellis/spec/guides/index.md`.
- GitNexus impact for `BusinessInvokeRouteStrategy`: LOW risk, direct hits are
  existing `InvokeRouteStrategyTest` methods.
- Verification:
  `mvn clean -Dtest=InvokeRouteStrategyTest test` passed with 10 tests.
  `mvn test` passed with 413 tests.
  `git diff --check` passed.
