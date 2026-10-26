# FinBridge Coding Agent Rules

## Project Constraints

- Use JDK 21. Run the Maven Wrapper with Java 21; do not rely on the developer shell's default JDK.
- Follow the active approved design in `docs/architecture/m0-proposal-v2.md` and accepted ADRs. Proposed decisions remain proposals until the user approves them.
- Do not implement a later milestone before its approval gate. M0 spike code is isolated under `m0-spikes/` and must not become a product module.
- Use only synthetic applicants, tenants, loan data, risk data, and provider results.
- Do not add concrete provider implementations to the host runtime classpath.
- Do not use floating-point types for monetary values or activate a loan before confirmed disbursement.
- If implementation requires changing an invariant, domain boundary, transaction guarantee, or accepted ADR, stop and request the design update before coding.

## Required Quality Gates

Before declaring a Java milestone complete, run from repository root under JDK 21:

```bash
./mvnw clean verify
```

This is a blocking gate and runs Maven Enforcer, SpotBugs, PMD, tests, and JaCoCo. Do not disable, skip, or lower a gate to make a build pass. Fix findings or propose a narrowly scoped, documented suppression with rationale and owner.

See `docs/quality/quality-gates.md` for the configured plugins, thresholds, report locations, suppression review, and current M0 limitations.

- **Static analysis:** SpotBugs runs at maximum effort with low threshold; zero findings are allowed. PMD correctness, best-practice, style, and design rules are blocking.
- **Size and complexity:** PMD rejects classes above 500 NCSS, methods/constructors above 50 NCSS, cyclomatic complexity above 10, and cognitive complexity above 15. God-class, deep nesting, and excessive parameter-list findings are also blocking.
- **Coverage:** JaCoCo line coverage must be strictly greater than 70% for the bundle, each production class, and each concrete production method. Configured threshold is 71%. Exclude only generated code and test classes; do not exclude production code to evade the gate. Add behavioral tests rather than tests that merely execute lines.
- **Tests:** Unit tests cover deterministic domain rules; integration tests use PostgreSQL/Testcontainers and real plugin JARs for architectural claims. Mocks do not replace required plugin, persistence, concurrency, or recovery integration evidence.
- **Architecture:** Add ArchUnit/module-boundary tests beginning in M1. Enforce dependency direction, prevent Lending Core imports of provider implementations, and prove host packaging omits concrete providers.
- **Coverage scope:** Apply production coverage thresholds to production modules. Test-only modules (for example `integration-tests`) may skip JaCoCo because they contain no production classes, but their test sources must still run through Surefire and the blocking PMD rules.

## Required Design and Implementation Review

For each milestone and substantial change, complete `docs/quality/architecture-review-checklist.md` before reporting completion. Compare the actual diff and tests against the active proposal/ADRs; do not infer conformance from a passing build. Record requirement -> module/class -> test -> documentation links for important claims. Explain deviations and update the proposal/ADR before implementing an approved change. Review findings and unresolved gaps must be reported before a summary of completed work.

## Reporting

Report the exact commands and results actually observed. State which gates were not runnable and why. Never claim a test, coverage threshold, architecture invariant, or production guarantee passed without evidence.
