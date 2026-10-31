# FinBridge Coding Agent Rules

## Project Constraints

- Use JDK 21. Run the Maven Wrapper with Java 21; do not rely on the developer shell's default JDK.
- Follow the current platform specification in `docs/architecture/01-platform-spec.md` and accepted ADRs. Proposed decisions remain proposals until the user approves them.
- Do not implement work outside the user-approved scope. Historical spike code is isolated under `m0-spikes/` and must not become a product module.
- Use only synthetic applicants, tenants, loan data, risk data, and provider results.
- Do not add concrete provider implementations to the host runtime classpath.
- Do not use floating-point types for monetary values or activate a loan before confirmed disbursement.
- If implementation requires changing an invariant, domain boundary, transaction guarantee, or accepted ADR, stop and request the design update before coding.

## Required Quality Gates

Before declaring a Java change complete, run from repository root under JDK 21:

```bash
./mvnw clean verify
```

This is a blocking gate and runs Maven Enforcer, SpotBugs, PMD, tests, and JaCoCo. Do not disable, skip, or lower a gate to make a build pass. Fix findings or propose a narrowly scoped, documented suppression with rationale and owner.

See `docs/quality/quality-gates.md` for the configured plugins, thresholds, report locations, and suppression review.

- **Static analysis:** SpotBugs runs at maximum effort with low threshold; zero findings are allowed. PMD correctness, best-practice, style, and design rules are blocking.
- **Size and complexity:** PMD rejects classes above 500 NCSS, methods/constructors above 50 NCSS, cyclomatic complexity above 10, and cognitive complexity above 15. God-class, deep nesting, and excessive parameter-list findings are also blocking.
- **Coverage:** JaCoCo line coverage must be strictly greater than 70% for the bundle, each production class, and each concrete production method. Configured threshold is 71%. Exclude only generated code and test classes; do not exclude production code to evade the gate. Add behavioral tests rather than tests that merely execute lines.
- **Tests:** Unit tests cover deterministic domain rules; integration tests use PostgreSQL/Testcontainers and real plugin JARs for architectural claims. Mocks do not replace required plugin, persistence, concurrency, or recovery integration evidence.
- **Architecture:** Use ArchUnit/module-boundary tests to enforce dependency direction, prevent Lending Core imports of provider implementations, and prove host packaging omits concrete providers.
- **Coverage scope:** Apply production coverage thresholds to production modules. Test-only modules (for example `integration-tests`) may skip JaCoCo because they contain no production classes, but their test sources must still run through Surefire and the blocking PMD rules.

## Required Regression and E2E Verification

Before declaring a substantial change complete, run all applicable regression gates from the repository root. Do not treat compilation or unit tests alone as end-to-end evidence.

1. **Java regression gate:** Run `./mvnw --batch-mode --no-transfer-progress clean verify` under JDK 21. Report the exact result, test totals, and Enforcer, PMD, SpotBugs, and JaCoCo outcomes. Do not skip tests or quality checks.
2. **Frontend regression gate:** Run `npm ci`, `npm run build`, `npm test`, and `npm audit --omit=dev` in `frontend/`. Report production-only audit scope accurately; do not imply development dependencies were audited if they were omitted.
3. **Compose configuration and smoke gate:** Run `docker compose config --quiet`, then `docker compose up -d`. Verify service health with `docker compose ps`, inspect startup failures with `docker compose logs`, confirm PostgreSQL is healthy and has the expected synthetic seed records, and request the Console at `http://localhost:5173`. Run `git diff --check`. After verification, stop the environment with `docker compose down` unless the user asked to leave it running. Use `docker compose down -v` only when intentionally discarding local data and reinitializing the database.
4. **Full API E2E gate:** Only claim this gate when a runnable API host is included and started, not from the current UI+PostgreSQL Compose smoke test. Verify tenant and admin flows over HTTP through the configured authentication boundary. Tenant identity must come from the trusted Spring Security `FinBridgePrincipal`; admin routes must require `ROLE_FINBRIDGE_ADMIN`. Cover tenant isolation/denial, synthetic application creation, risk/workflow and loan/repayment views, exact decimal transport, disbursement/reconciliation ownership and ambiguity, and admin plugin registry/routing/lifecycle controls. Do not add a fake identity provider, accept caller-selected tenant IDs, or trigger real provider effects for testing. If no runnable authenticated API host is available, state that full API E2E is blocked and list the missing host/auth prerequisites; do not report the UI+database smoke test as API E2E.

Record requirement -> implementation -> test/evidence -> documentation traceability in `docs/quality/architecture-review-checklist.md`. Report exact commands and observed results, including any gate not run and why.

## Required Design and Implementation Review

For each substantial change, complete `docs/quality/architecture-review-checklist.md` before reporting completion. Compare the actual diff and tests against `docs/architecture/01-platform-spec.md` and accepted ADRs; do not infer conformance from a passing build. Record requirement -> module/class -> test -> documentation links for important claims. Explain deviations and update the platform specification or ADR before implementing an approved design change. Review findings and unresolved gaps must be reported before a summary of completed work.

## Reporting

Report the exact commands and results actually observed. State which gates were not runnable and why. Never claim a test, coverage threshold, architecture invariant, or production guarantee passed without evidence.
