# Architecture, Design, and Implementation Review

Use this checklist before each milestone completion and for changes that cross domain/module boundaries. It complements automated compilation, static analysis, architecture tests, and coverage; it does not replace them.

## Plan and Scope

- [ ] Confirm the active approved spec, milestone, and applicable ADR status.
- [ ] Confirm every changed file belongs to the current milestone; no later-milestone scope was introduced.
- [ ] Map relevant requirements/invariants to changed modules/classes, tests, and documentation.
- [ ] Record any design deviation and obtain approval/update the proposal or ADR before treating it as accepted.

## Architecture and Dependencies

- [ ] Maven dependency direction matches the approved module graph; no circular dependency was introduced.
- [ ] Lending Core depends on gateway abstractions, not concrete providers or provider DTOs.
- [ ] Provider JARs are external and absent from the host runtime package/dependency tree.
- [ ] SPI shared types retain one parent-loaded class identity; plugin-private dependencies remain isolated.
- [ ] Tenant identity and ownership are derived/verified server-side at every boundary.
- [ ] State machines have explicit valid transitions and invalid transition tests.

## Domain and Consistency

- [ ] Application, risk assessment, loan, repayment plan, installments, payment, and workflow states have not been conflated.
- [ ] Loan and repayment plan creation are locally atomic and protected against duplicate origination.
- [ ] Money uses `BigDecimal`; rounding, final installment, principal reconciliation, and due-date policy are explicit and tested.
- [ ] External calls occur outside long database transactions; durable intent/progress precedes side effects.
- [ ] Unknown payment outcomes remain unknown and cannot be blindly retried or failed over.
- [ ] Payment provider ownership is immutable; status/reconciliation uses the original plugin/version.
- [ ] Loan and repayment plan activation requires persisted confirmed disbursement success.
- [ ] Crash/retry recovery is idempotent at each changed workflow step.

## Plugin Lifecycle and Security

- [ ] Install validation, explicit activation, routing eligibility, draining, leases, and unload conditions are respected.
- [ ] Unresolved payment ownership and required bindings prevent unsafe artifact removal.
- [ ] Timeouts, TCCL, executors, resources, registry references, and failure states are handled and observable.
- [ ] Class-loader separation is not described as a sandbox; plugin trust boundary remains clear.
- [ ] Admin/tenant authorization, tenant-scoped reads, artifact path controls, and trace data minimization are preserved.

## Evidence and Operations

- [ ] `./mvnw clean verify` passed on JDK 21 with SpotBugs, PMD, tests, and JaCoCo gates enabled.
- [ ] Per-class and per-method line coverage exceed 70%; report excluded generated code and explain it.
- [ ] Architecture tests and real-artifact/database tests were run where relevant; mocks did not replace required evidence.
- [ ] Each major claim links to implementation and a test that demonstrates it.
- [ ] Documentation reflects actual behavior and limitations; commands/results were not fabricated.
- [ ] Remaining risks, unavailable tests, suppressions, and operational recovery steps are explicit.

## Review Record

| Item | Record |
|---|---|
| Milestone/change | |
| Reviewer/date | |
| Relevant requirements and ADRs | |
| Implementation/test/doc references | |
| Deviations and approvals | |
| Quality gate command/result | |
| Open findings/risks | |

## M1 Review Record (2026-10-26)

| Item | Record |
|---|---|
| Milestone/change | M1 Plugin Foundation |
| Reviewer/date | Coding-agent review, 2026-10-26 |
| Relevant requirements and ADRs | v2 proposal sections 4, 5, 10, 17 M1; ADR-001 through ADR-006 drafts |
| Implementation/test/doc references | `finbridge-spi`; `finbridge-plugin-runtime`; `finbridge-host`; `plugins/alphapay-v1`; `plugins/alphapay-v2`; `PluginRuntimeIntegrationTest`; `HostArchitectureTest`; `ArchitectureBoundaryTest`; `HostPackagingTest`; `docs/architecture/01-system-overview.md` through `04-plugin-lifecycle.md` |
| Deviations and approvals | Provider fixture uses test-scoped Maven artifacts to construct real fat plugin JARs; host packaging is inspected independently. Runtime bindings are in-memory foundation only, as scoped for M1. Typed risk SPI added in M1; no risk provider or lending decision workflow is implemented. |
| Quality gate command/result | `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, full 9-project reactor; tests 4 SPI + 1 helper v1 + 1 AlphaPay v1 + 1 helper v2 + 1 AlphaPay v2 + 15 runtime + 2 host + 3 architecture/packaging = 28 passed, 0 failed/errors/skipped. SpotBugs 0 findings; PMD no violations; production-module JaCoCo bundle/class/method checks met. The empty top-level aggregator and test-only integration-tests module have no production classes; JaCoCo reports those modules as not applicable. |
| Open findings/risks | Test host uses a provider-free parent test class loader to model a host classpath without providers; the minimal host composition JAR has no Spring Boot API/server yet. No durable binding store, plugin audit persistence, security/API, restart recovery, BetaPay, or risk provider fixtures in M1. These are later milestones, not claimed implemented. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Typed, framework-neutral contracts | `finbridge-spi` | `PluginContractsTest` | `02-spi-contract-design.md` |
| SPI discovery from external provider artifact | `PluginDiscovery`, AlphaPay service descriptors | `loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation` | `01-system-overview.md`, `02-spi-contract-design.md` |
| Per-plugin class identity and private dependency isolation | `PluginClassLoader`, assembled AlphaPay v1/v2 JARs | Integration test checks distinct helper class/loader and shared parent SPI class | `03-classloader-isolation.md` |
| Lifecycle, invocation lease, drain and unload | `PluginHandle`, `PluginRuntime` | runtime integration/validation tests | `04-plugin-lifecycle.md` |
| Optimistic capability binding and invocation pinning | `PluginBindingRegistry`, `PluginInvocationLease` | `bindingUpdatesAreOptimisticAndRoutingPinsSelectedHandle` | `04-plugin-lifecycle.md` |
| Host avoids provider dependency/package | `finbridge-host` module graph and artifact | host ArchUnit and JAR inspection tests | `01-system-overview.md` |

## M2 Review Record (2026-10-26)

| Item | Record |
|---|---|
| Milestone/change | M2 Lending Core |
| Reviewer/date | Coding-agent review, 2026-10-26 |
| Relevant requirements and ADRs | v2 proposal sections 3-7, 11 and M2 scope; accepted ADR-010 repayment policy; ADR-008/009 consistency constraints preserved at model boundary |
| Implementation/test/doc references | `finbridge-lending-core`; `LoanApplication`, `LoanProduct`, `RiskAssessment`, `Loan`, `RepaymentPlan`, `RepaymentInstallment`; JDBC repositories; Flyway V1; 43 M2 tests; `docs/architecture/02-lending-domain-model.md`; `docs/architecture/06-repayment-plan-design.md` |
| Deviations and approvals | User confirmed DECIMAL128 intermediate math, scale-2 HALF_UP rounding, final installment residual adjustment, and actual-disbursement anchored monthly dates with month-end clamp before implementation. Risk integration/decision remains M3. Durable payment workflow/disbursement/activation remains M4. |
| Quality gate command/result | Root `JAVA_HOME="$(/usr/libexec/java_home -v 21)" PATH="$JAVA_HOME/bin:$PATH" ./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, full 10-project reactor. 71 total tests passed: M2 43, M1/SPI/plugins/runtime/host/integration 28. SpotBugs zero findings, PMD clean, production JaCoCo class/method gates met. |
| Open findings/risks | Loan activation guard exists but no payment-confirmation coordinator yet. Application API has not been implemented, so the application command is a domain/application service input, not yet an HTTP boundary; tenant identity must be replaced by authenticated principal context when the host API is added. No risk provider orchestration/policy execution in M2. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Application state machine and product validation | `LoanApplication`, `LoanProduct`, `LendingApplicationService` | `DomainStateTest`, `LendingApplicationServiceTest` | `02-lending-domain-model.md` |
| Idempotent application create and optimistic updates | `JdbcLoanApplicationRepository` | PostgreSQL replay/conflict/concurrent claim and version tests | `02-lending-domain-model.md` |
| Normalized risk evidence model and persistence | `RiskAssessment`, `JdbcRiskAssessmentRepository` | PostgreSQL round-trip/malformed-data/write/serialization tests | `02-lending-domain-model.md` |
| Loan and plan atomic origination, one per application | `LoanOriginationService`, `JdbcLendingAggregateRepository` | PostgreSQL aggregate/duplicate/partial recovery tests | `02-lending-domain-model.md` |
| BigDecimal amortization and user-approved rounding | `RepaymentScheduleCalculator` | `RepaymentScheduleCalculatorTest` | `06-repayment-plan-design.md`, ADR-010 |
| Actual-disbursement due-date anchor and activation guard | `MonthlyDueDatePolicy`, `RepaymentPlan`, `Loan` | domain month-end and pre-confirmation tests | `06-repayment-plan-design.md`, ADR-009/010 |

## M2 Review Record (2026-10-26)

| Item | Record |
|---|---|
| Milestone/change | M2 Lending Core |
| Reviewer/date | Coding-agent review, 2026-10-26 |
| Relevant requirements and ADRs | v2 proposal sections 3-7, 11 and 16; accepted ADR-010 for repayment math/date policy; ADR-008/009 workflow and activation constraints applied to model boundaries |
| Implementation/test/doc references | `finbridge-lending-core`; domain/application/persistence/config packages; Flyway V1; `DomainStateTest`, `RepaymentScheduleCalculatorTest`, `JdbcLendingCoreIntegrationTest`, `LendingApplicationServiceTest`, `LoanOriginationServiceTest`, `RepaymentPlanServiceTest`, `LendingCoreConfigurationTest`; `02-lending-domain-model.md`; `06-repayment-plan-design.md` |
| Deviations and approvals | Rounding and due-date rules confirmed by user before implementation: DECIMAL128 intermediates, scale-2 HALF_UP, final installment adjustment, disbursement-date anchor with month-end clamping. Provider risk retrieval/policy execution remains M3; payment/workflow recovery remains M4. No API/UI in M2. |
| Quality gate command/result | Final result recorded after root `./mvnw --batch-mode --no-transfer-progress clean verify`. |
| Open findings/risks | Payment confirmation and loan activation operation are not wired in M2; only domain guard exists. Host is not yet wired with data source/migration startup. Product rate and accepted decision thresholds remain synthetic. M2 integration tests require Docker for PostgreSQL Testcontainers. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Application state and tenant-scoped persistence | `LoanApplication`, `LendingApplicationService`, `JdbcLoanApplicationRepository` | domain and PostgreSQL tests for transitions, optimistic lock, tenant scope and idempotency | `02-lending-domain-model.md` |
| Product version/amount/term validation | `LoanProduct`, `JdbcLoanProductRepository` | `DomainStateTest`, `JdbcLendingCoreIntegrationTest.productRepositoryReadsVersionedTermsAndCurrency` | `02-lending-domain-model.md` |
| Deterministic amortization and rounding | `RepaymentScheduleCalculator` | `RepaymentScheduleCalculatorTest` | `06-repayment-plan-design.md`, ADR-010 |
| Due-date anchoring and month-end clamp | `MonthlyDueDatePolicy`, `RepaymentPlan.activate` | `DomainStateTest.dueDatesClampMonthEndsWithoutLosingOriginalAnchor` | `06-repayment-plan-design.md` |
| One loan/plan/installment set persisted atomically | `JdbcLendingAggregateRepository.originate` | PostgreSQL Testcontainers atomic/duplicate/recovery tests | `02-lending-domain-model.md` |
| Loan not ACTIVE before confirmed disbursement fields | `Loan` state constructor/transition | `DomainStateTest.loanCannotActivateBeforePaymentIntentAndConfirmedTimestamp` | `02-lending-domain-model.md`; ADR-009 |
| Risk evidence stores normalized values and provider references | `RiskAssessment`, `JdbcRiskAssessmentRepository` | PostgreSQL round-trip, invalid JSON, and write failure tests | `02-lending-domain-model.md` |

## M3 Review Record (2026-10-27)

| Item | Record |
|---|---|
| Milestone/change | M3 Risk Integration, Approval, and Durable Risk-Step Recovery |
| Reviewer/date | Coding-agent review, 2026-10-27 |
| Relevant requirements and ADRs | v2 proposal M3 scope and risk policy; accepted ADR-012 synthetic risk policy; accepted ADR-013 durable read-only risk-step recovery; existing ADR-005 tenant capability routing and ADR-011 PostgreSQL persistence |
| Implementation/test/doc references | `finbridge-risk-gateway` (`RiskDataGateway`, `SyntheticRiskDecisionPolicy`); risk providers `plugins/creditscorex`, `plugins/blacklisty`, `plugins/fraudsignalz`; `PluginRuntime.installRiskProvider` and risk provider discovery; `RiskAssessmentService`; workflow and assessment JDBC repositories; Flyway V1; external-JAR runtime/gateway tests; PostgreSQL workflow integration tests; `ADR-012`, `ADR-013`, `m0-proposal-v2.md` |
| Deviations and approvals | Implements only synthetic read-only risk providers and user-approved demo thresholds. Risk calls occur outside database transactions. A running workflow reserves one assessment ID; interrupted provider reads may be repeated, while completed assessments are returned without provider calls. Payment intent, disbursement, payment ambiguity recovery, API, and UI remain outside M3. The missing reserved pending assessment now fails closed rather than attempting an unowned insert; reservation and active workflow references are created atomically at workflow start. |
| Quality gate command/result | With `JAVA_HOME` set to any JDK 21 installation: `./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, full 14-module reactor. 106 tests passed, 0 failures/errors/skips. SpotBugs reported zero findings; PMD had no violations; JaCoCo bundle/class/method checks met configured thresholds in all production modules. PostgreSQL Testcontainers and external plugin JAR tests ran. |
| Open findings/risks | Risk policy is illustrative, not validated underwriting. Provider fixtures are synthetic and repeatable; real providers require re-evaluation of retry semantics. The executor bounds risk provider calls but does not sandbox plugins. Existing multi-tenant identity is supplied to service/repository boundaries; authenticated HTTP principal enforcement remains a later API concern. No downstream loan origination follows an approval in M3. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Mandatory credit score/blacklist, optional fraud, deterministic policy thresholds and precedence | `SyntheticRiskDecisionPolicy` | `SyntheticRiskDecisionPolicyTest` covers score boundaries, mandatory missing/invalid data, optional fraud outage, blacklist and critical-fraud rejection | ADR-012; proposal v2 risk policy section |
| Load risk providers from external artifacts with shared SPI and expected capability validation | `PluginRuntime.installRiskProvider`, `PluginDiscovery.discoverRiskProvider` | `PluginRuntimeRiskIntegrationTest` loads and invokes an assembled CreditScoreX artifact with provider-free parent loader; `RiskDataGatewayIntegrationTest` loads all three risk JARs | ADR-005; ADR-012 |
| Tenant capability routing, bounded execution, normalized provider failures and stable execution IDs | `RiskDataGateway` | `RiskDataGatewayIntegrationTest`, `RiskDataGatewayFailureTest` exercise routing, missing binding, timeout/cancellation, executor rejection, provider exception and typed values | ADR-012 |
| Persist workflow/attempt/assessment ownership before read; allow safe retry after interruption | `JdbcLendingAggregateRepository`, `JdbcRiskAssessmentRepository` | PostgreSQL `JdbcLendingCoreIntegrationTest` covers start/resume, interrupted attempt history, stale claims, completion rollback, completed-claim replay and atomic application/workflow/assessment completion | ADR-013 |
| Do not repeat completed provider reads; scope assessment lookup by tenant | `RiskAssessmentService` | `RiskAssessmentServiceTest` covers completed workflow replay without provider calls and tenant-scoped latest assessment lookup | ADR-013 |
| Preserve M3 boundary; do not originate loans or invoke payment | `RiskAssessmentService`, Lending Core module | Policy/service tests assert assessment outcome; module contains no payment intent or disbursement workflow step | ADR-013; proposal v2 M3/M4 gates |

## M4 Review Record (2026-10-27)

| Item | Record |
|---|---|
| Milestone/change | M4 Payment and Durable Disbursement Workflow |
| Reviewer/date | Coding-agent review, 2026-10-27 |
| Relevant requirements and ADRs | v2 proposal M4 scope; accepted ADR-007 immutable payment owner/idempotency and ambiguity handling; accepted ADR-008 durable workflow; accepted ADR-009 activate only after confirmed disbursement; ADR-010 due-date policy; ADR-011 PostgreSQL persistence |
| Implementation/test/doc references | SPI `PaymentProvider`/`PaymentResult`/`PaymentTransactionRef`; `PluginRuntime` owner-pinned invocation and retention-policy hook; `finbridge-payment-gateway` bounded gateway; `DisbursementService`; `JdbcLendingAggregateRepository` and `JdbcDisbursementRepository`; Flyway V1 `payment_transactions` and workflow payment refs; AlphaPay v1/v2 and BetaPay external artifacts; payment-gateway external fat-JAR tests; PostgreSQL `JdbcLendingCoreIntegrationTest`; ADR-007/009; proposal v2 |
| Decisions/limits | Ambiguous calls are never automatically resubmitted and never fail over. An unresolved query uses the exact stored plugin ID/version and either provider transaction ID or (host payment transaction ID, original client idempotency key). Provider status query returns a typed result so reconciliation can persist a discovered provider reference. Definitive failure is terminal; no new payment-intent retry policy was approved or implemented. Loan and plan activation occurs in one local DB transaction only after `SUCCEEDED` has been persisted. Payment/provider calls remain outside DB transactions. Lending Core provides the unresolved-owner predicate and Plugin Runtime accepts a retention policy, but host lifecycle wiring is M5; deployment operations must retain unresolved owner artifacts until then. |
| Quality gate command/result | With `JAVA_HOME` set to any JDK 21 installation, `./mvnw --batch-mode --no-transfer-progress clean verify` passed for the full 16-project reactor. Surefire: 148 tests passed, 0 failures/errors/skips, including 37 PostgreSQL integration tests and external AlphaPay/BetaPay artifact tests. SpotBugs: 0 findings; PMD: clean; all production JaCoCo checks met. Test-only integration module ran its tests; JaCoCo is not applicable because it has no production classes. |
| Open findings/risks | Payment providers are synthetic; query semantics and SUCCEEDED settlement meaning require review before using real providers. Host lifecycle composition does not yet connect the retention predicate to drain/unload; this is M5 scope, so deployment operations must retain owner artifacts. The MVP does not auto-retry definitive failure. The single-host transaction/worker model does not provide multi-host fencing. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Persist payment intent, payload fingerprint and provider identity before side effect; matching idempotency replay does not redispatch | `JdbcDisbursementRepository.claimDisbursement`, `payment_transactions` | PostgreSQL integration test verifies initial dispatch claim, exact replay reuses transaction, owner/payload conflict rejects | ADR-007; ADR-008 |
| Invoke provider outside transaction and pin invocations to original plugin version | `DisbursementService`, `PaymentGateway` | `PaymentGatewayIntegrationTest` invokes and queries external AlphaPay v1 fat JAR; PostgreSQL ambiguity test verifies current binding/version cannot replace stored owner | ADR-005; ADR-007 |
| Persist UNKNOWN/PROCESSING after ambiguous response; recovery queries client key/provider ref and never blind-resubmits | `PaymentTransactionRef`, `PaymentGateway`, `DisbursementService` | External JAR status-query tests cover SUCCEEDED/FAILED/PROCESSING/UNKNOWN; PostgreSQL test covers UNKNOWN->SUCCEEDED and terminal local state; repeated submission is disabled | ADR-007; ADR-008 |
| Provider success including recovery-discovered provider reference is persisted before activation | SPI `PaymentResult`, `recordDisbursementOutcome` | PostgreSQL reconciliation test checks provider reference, transaction SUCCEEDED, and owner release | ADR-007 |
| Atomically activate loan, schedule, due dates, application, and workflow after persisted success only | `Loan.confirmDisbursement`, `RepaymentPlan.activate`, `JdbcDisbursementRepository.finalizeSuccessTransaction` | PostgreSQL tests assert pending state for unknown/failed and atomic activation after success, due-date finalization and replay after persisted-success interruption | ADR-009; ADR-010 |
| Expose unresolved-owner status for lifecycle retention | `LendingAggregateRepository.hasUnresolvedPaymentOwner`; `PluginRuntime.drain/unload` retention-policy parameter | PostgreSQL tests check unresolved owner retention and release after terminal resolution; runtime tests verify the retention hook blocks lifecycle operations | ADR-007; lifecycle composition is M5 |

## M5 Review Record (2026-10-27)

| Item | Record |
|---|---|
| Milestone/change | M5 Hot Swap, Security, and Observability |
| Reviewer/date | Coding-agent review, 2026-10-27 |
| Relevant requirements and ADRs | Approved M5 baseline; accepted ADR-005 durable single-host bindings and authenticated facades; accepted ADR-006 persisted artifact lifecycle/retention and future-route upgrade/rollback; accepted ADR-008 query-only startup payment recovery |
| Implementation/test/doc references | `finbridge-host` lifecycle, persistence, principal-bound facades, and recovery runner; `finbridge-plugin-runtime` binding publication and bounded operation metrics; Lending Core unresolved-owner/recovery persistence; PostgreSQL control-plane integration tests; assembled external artifact lifecycle tests; `HostAuthorizationTest`, `PluginLifecycleManagerTest`, `PluginRecoveryRunnerTest`, `PluginArchitectureTest`; Flyway V2; ADR-005/006/008 |
| Decisions/limits | No HTTP endpoints or credential issuance; host facades consume a FinBridge-owned Spring Security principal. Local prebuilt artifacts only; no signing/upload/sandbox. Startup recovery is single-host, query-only, and never retries terminal failures. |
| Quality gate command/result | At the M5 checkpoint, with `JAVA_HOME` set to JDK 21, `./mvnw --batch-mode --no-transfer-progress -pl finbridge-host -am verify` passed all 15 reactor projects, 60 host tests, SpotBugs, PMD and JaCoCo. The later final root gate is recorded in the M7 review below. |
| Open findings/risks | M5 facades enforce principal/role authorization in-process; the local HTTP/OIDC composition is separately recorded in M7 ADR-014. Startup recovery and binding publication remain single-host; class-loader isolation is not a sandbox; artifacts are local allowlisted prebuilt files without signature verification. |

Requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Restore only approved plugin artifacts whose persisted checksums still match | `PluginLifecycleManager.restoreAtStartup`, `restoreArtifact`, `artifactIdentityMatches`; approved local artifact root | `PluginLifecycleManagerTest.startupRestoreActivatesVerifiedArtifactThenRestoresDurableBinding`, missing-artifact and checksum-mismatch tests; PostgreSQL `PluginControlPlaneIntegrationTest.artifactIdentityIsImmutableAndLifecycleAuditIsAppendOnly` | ADR-006; proposal v2 M5 |
| Persist and restore tenant capability bindings with optimistic revisions | `PluginControlPlaneRepository.bindingSwitch`, `rollbackBindingAndRestorePrevious`; `PluginRuntime.publishBinding`/`restoreBinding` | PostgreSQL binding CAS/removal-revision and upgrade/rollback integration tests; `PluginLifecycleManagerTest.restoreAtStartupActivatesVerifiedArtifactThenRestoresDurableBinding` | ADR-005; ADR-006 |
| Require tenant principal for tenant actions and admin authority for lifecycle/binding controls | `FinBridgePrincipal`, `HostAuthorization`, `TenantLendingFacade`, `PluginAdminFacade` | `HostAuthorizationTest` covers principal-derived tenant identity, forged/substituted identities, and admin-role rejection; `PluginLifecycleManagerTest.lifecycleReadOperationsRequireAdministratorRole` | ADR-005; `m0-proposal-v2.md` M5 baseline |
| Prevent drain/unload while bindings, leases, or unresolved payment owners remain | `PluginLifecycleManager.drain`, `unload`, `drainRetired`; `PluginRuntime.drain`/`unload`; Lending Core `hasUnresolvedPaymentOwner` | `PluginLifecycleManagerTest.unbindPublishesPersistedRevisionAndDrainHonorsRetainedOwnerWork`, retired binding/lease/reconciliation/retention cases; PostgreSQL unresolved-owner persistence test | ADR-006; ADR-007 |
| Upgrade/rollback changes future routing while preserving historical transaction ownership | `PluginLifecycleManager.upgrade`, `rollback`, `recoverFailedUpgrade`; immutable payment transaction owner in Lending Core | PostgreSQL upgrade/rollback revision tests; lifecycle tests preserve previous binding on failure and drain only after route publication; payment tests query pinned owner | ADR-006; ADR-007 |
| Recover interrupted payment dispatch as UNKNOWN and query only original provider | `PluginRecoveryRunner`, `DisbursementService.recoverPayments`, persisted owner/query reference | `PluginRecoveryRunnerTest.recoveryRunsRiskAndPaymentRecoveryOnlyOnce`; PostgreSQL recovery tests cover SUBMISSION_IN_PROGRESS to UNKNOWN and original-owner query without resubmission | ADR-008; ADR-007 |
| Audit lifecycle changes and expose bounded metrics without high-cardinality identities | `PluginControlPlaneRepository.recordEvent`; `PluginOperationMetrics`; risk/payment gateway instrumentation | PostgreSQL append-only audit history tests; `PluginRuntimeValidationTest.operationMetricsOnlyExposeBoundedOperationAndOutcomeTags`; payment/risk metric tests | ADR-006; `m0-proposal-v2.md` M5 security and observability |

## M6 Review Record (2026-10-28)

| Item | Record |
|---|---|
| Milestone/change | M6 FinBridge Console: React Console and Spring MVC API adapters |
| Reviewer/date | Coding-agent review, 2026-10-28 |
| Relevant requirements and ADRs | Approved proposal v2 sections 12-13 and M6 scope; M5 accepted ADR-005/006/008 boundaries; M4 accepted ADR-007/008/009/010 invariants |
| Implementation/test/doc references | `frontend/src/main.tsx`, `frontend/src/api.ts`, `frontend/src/styles.css`; `finbridge-host` `TenantConsoleController`, `AdminConsoleController`, `ConsoleApiExceptionHandler`, `TenantConsoleReadRepository`, `TenantLendingFacade`, `PluginAdminFacade`; `TenantConsoleControllerTest`, `AdminConsoleControllerTest`, `HostAuthorizationTest`, `TenantConsoleReadRepositoryTest`, `PluginControlPlaneIntegrationTest`, frontend `App.test.tsx` and `api.test.ts`; `frontend/vite.config.ts` |
| Deviations and approvals | No domain invariant, transaction guarantee, dependency direction, or accepted ADR changed. The Console is a React client and the host exposes MVC adapters; runnable API packaging and HTTP identity composition were added later under ADR-014. The Vite development server proxies `/api` to the local API host on port 8080. Workflow correlation IDs are not persisted in the existing schema, so the UI explicitly reports them unavailable rather than synthesizing history. |
| Quality gate command/result | With `JAVA_HOME` set to JDK 21, `./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, all 16 reactor modules; 238 tests passed, including 72 host tests; PMD clean, SpotBugs zero findings, and all production JaCoCo checks met. Frontend `npm run build` passed; `npm test` passed (4 tests); `npm audit --omit=dev` reported 0 production dependency vulnerabilities; `git diff --check` passed. |
| Open findings/risks | The containing application must supply a trusted `FinBridgePrincipal` in Spring Security's context and enforce/populate `ROLE_FINBRIDGE_ADMIN`; M6 does not issue credentials or configure an identity provider. Production static asset serving, same-origin/API proxying, and an end-to-end HTTP security/filter integration test are not included. The Console and lifecycle publication retain M5's single-host guarantees and limitations; class-loader isolation is not a sandbox, and plugin artifacts are local allowlisted prebuilt files without signature verification. The dependency audit was production-only; npm reported development dependency advisories during installation. |
| Local Compose follow-up | At the M6 checkpoint, Compose started PostgreSQL and one Vite Console frontend service; API requests proxied to a separately supplied local backend. The authenticated API and identity stack were added later under ADR-014. The borrower page is a Console route, not a separately containerized client. |

## Approved Authenticated Borrower Demo Layer (Implemented; Final Security Review Open)

The user approved the M7 borrower API demo boundary in `docs/adr/ADR-014-authenticated-borrower-demo-api.md`: Keycloak local OIDC with Authorization Code + PKCE, same-origin server-side session and CSRF controls, issuer/subject-to-membership mapping, local HTTPS, and a synthetic borrower journey. Keycloak 26.3.5 is selected for the local demo only, not for production identity. The API module, borrower routes, identity membership mapping, synthetic account import, HTTPS Compose proxy, opt-in fixture registration/routing bootstrap, borrower page, and security tests are implemented under `finbridge-demo-app`, `frontend`, `deploy`, and Flyway V3. A detached clean worktree containing staged, unstaged, and untracked changes passed Java, frontend, Compose, service-health, and authenticated HTTP checks. Flyway applied V1-V3 and seeded two tenants, one product, six subject mappings, three bootstrap plugin artifacts, and eight tenant bindings. Live E2E passed real Keycloak OIDC/PKCE; cookie attributes; CSRF logout and post-logout denial; tampered state and invalid authorization-code denial; hostile tenant/applicant/role/scenario fields; exact decimal transport; approval/review/rejection fixtures; origination/disbursement/reconciliation; BetaPay routing; FraudSignalZ install/binding; AlphaPay rollback; tenant isolation; and borrower/admin route separation. A short-timeout run verified server-side session expiry. The Node E2E uses `NODE_TLS_REJECT_UNAUTHORIZED=0` only for the generated local self-signed certificate; this is not production TLS. Remaining work is final security/release review; production identity and threat modeling remain out of scope.

| Approved requirement | Implementation | Evidence so far | Remaining verification |
|---|---|---|---|
| Authorization Code + PKCE and same-origin server-side session | `DemoSecurityConfiguration`, `DemoOidcUserService`, `DemoIdentityFilter`, `DemoSessionController`, `deploy/nginx/default.conf`, Keycloak realm | Six `DemoSecurityHttpTest` cases pass; live E2E checks PKCE S256, cookie attributes, CSRF logout, post-logout denial, and session expiry under a 5-second test override | Local TLS uses a generated self-signed certificate; E2E explicitly disables TLS verification for local testing only |
| Server-side issuer/subject mapping and mutually exclusive tenant/admin roles | Flyway V3 `demo_identity_memberships`, `DemoIdentityMembershipRepository`, `DemoOidcUserService`, `DemoIdentityFilter`, `DemoOidcUser` | Three `DemoOidcUserTest` cases cover borrower/admin role mapping, issuer mismatch and missing membership; HTTP and live E2E cover denial in both directions and cross-tenant object-ID denial | Local Keycloak users/mappings are demo-only; production identity remains unselected |
| Borrower application, risk/workflow, origination, repayment, disbursement and reconciliation over HTTP | `BorrowerApiController`, principal-scoped `TenantLendingFacade`, `/borrower` UI, `scripts/demo-http-e2e.mjs` | Live E2E verifies exact `1234.56`, server-mapped applicant/tenant despite hostile request fields, approval/review/rejection, no origination for non-approved applications, disbursement, reconciliation, and tenant isolation | ADR-014; proposal v2 section 17 demo 7; no real provider effects |
| Local authenticated cluster with synthetic fixtures | `compose.yaml`, `finbridge-demo-app/Dockerfile`, Keycloak realm import, `DemoPluginBootstrap`, Flyway V1-V3 | `docker compose config --quiet`; clean synthetic-only volume reset followed by `docker compose up -d --build`; `docker compose ps` showed PostgreSQL, Keycloak, and API healthy; pre-E2E seed counts: 2 tenants, 6 memberships, 1 product, 3 bootstrapped plugins, 8 bindings; HTTPS borrower Console HTTP 200; complete E2E passed | Local credentials/certificate only; stale artifact checksums intentionally fail closed, so rebuilding against existing demo volumes requires the documented intentional synthetic DB reset |

## M7 Demo Review Record (2026-10-28)

| Item | Record |
|---|---|
| Milestone/change | M7 authenticated local borrower demo, walkthroughs, and clean-worktree verification; final live-token callback limitations remain |
| Reviewer/date | Coding-agent review, 2026-10-28 |
| Relevant requirements and ADRs | Approved proposal v2 section 17 M7; accepted ADR-014 local OIDC/BFF boundary; ADR-005 trusted `FinBridgePrincipal`; ADR-007 immutable payment owner/ambiguity; ADR-009 activate only after confirmed disbursement; ADR-010 repayment policy |
| Implementation/test/doc references | `finbridge-demo-app`; `BorrowerApiController`; `DemoOidcUserService`; `DemoIdentityFilter`; `DemoIdentityMembershipRepository`; `DemoSecurityConfiguration`; `DemoSessionController`; `DemoPluginBootstrap`; `TenantLendingFacade`; Flyway V3; `compose.yaml`; `deploy/keycloak/realm-export.json`; `deploy/nginx/default.conf`; `frontend/src/main.tsx`; `scripts/demo-http-e2e.mjs`; `docs/guides/borrower-demo-usage.md`; ADR-014; README |
| Decisions/limits | Local Keycloak 26.3.5, Authorization Code + PKCE, server-side Spring Security session, CSRF, mutually exclusive borrower/admin authorities. Tenant scope derives only from server-side issuer/subject membership. Providers remain external JARs under `/app/plugins`. Synthetic data/providers only. No production identity/security guarantees. |
| Quality gate command/result | Historical M7 implementation evidence only; it predates the 2026-10-29 feedback remediation and is not the current release verdict. See the latest review record below for current-tree checks. |
| Frontend/Compose/API evidence | Historical M7 implementation evidence only. Counts and screenshots below are superseded by later code and test additions; do not use this row as current release evidence. |
| Open findings/risks | Live Keycloak callback E2E covers tampered state, invalid authorization code, and nonce mismatch using a valid Keycloak-signed ID token. Malformed, expired, not-yet-valid, wrong-issuer, wrong-audience, and untrusted-signature cases are tested through Spring Security's production provider/decoder with signed synthetic tokens, but not each injected through live Keycloak callbacks. MockMvc tests that inject mapped principals are not token-validation evidence. Local Node E2E disables TLS verification for the self-signed certificate and must remain local-only. Production threat modeling, production identity/security review, and deployment beyond the synthetic local demo remain out of scope. |

Requirement traceability:

| Requirement | Module/class | Test/evidence | Documentation |
|---|---|---|---|
| Server-side issuer/subject maps a trusted principal and tenant | `DemoOidcUserService`, `DemoIdentityMembershipRepository`, `DemoIdentityFilter`, `FinBridgePrincipal` | `DemoOidcUserTest`; `DemoSecurityHttpTest`; live Keycloak borrower/admin sessions | ADR-014; this review record |
| Borrower/admin roles are mutually exclusive | `DemoOidcUser`, `DemoSecurityConfiguration` | `DemoOidcUserTest`; `DemoSecurityHttpTest.administratorCannotUseBorrowerApiAndBorrowerCannotUseAdminApi`; live E2E denies both cross-role route directions | ADR-014; README |
| Application and risk result preserve exact decimal and tenant scope | `BorrowerApiController`, `TenantLendingFacade`, Lending Core risk workflow | `BorrowerApiControllerTest`; PostgreSQL Lending Core tests; live E2E `1234.56`, `APPROVE`, `APPROVED` and beta 404 | ADR-014; E2E script |
| Loan/plan activation follows persisted confirmed synthetic disbursement; status/reconcile retain provider ownership | `DisbursementService`, `TenantLendingFacade`, payment gateway | PostgreSQL disbursement/recovery tests; live E2E pending-to-active transition, payment status and reconciliation through original synthetic AlphaPay | ADR-007/009/010; ADR-014 |
| Host classpath omits concrete providers | `finbridge-demo-app/Dockerfile`, host packaging | `HostPackagingTest`; host architecture integration tests; image stores artifacts separately in `/app/plugins` | M1 architecture docs; ADR-014 |

M6 requirement traceability:

| Requirement | Module/class | Test evidence | Documentation |
|---|---|---|---|
| Responsive Console with all eight proposal views and honest processing/ambiguity states | `frontend/src/main.tsx`, `frontend/src/styles.css` | `App.test.tsx`; `npm run build` | Proposal v2 section 12; Architecture Explorer in the Console |
| Tenant-scoped Console reads/actions derive tenant from trusted principal, not request tenant selection | `TenantConsoleController`, `TenantLendingFacade`, `TenantConsoleReadRepository`, Lending Core tenant-scoped repositories | `TenantConsoleControllerTest`, `HostAuthorizationTest`, `TenantConsoleReadRepositoryTest`, PostgreSQL `JdbcLendingCoreIntegrationTest` | Proposal v2 sections 12-13; M5 ADR-005 |
| Admin plugin registry, audit, routing and lifecycle controls require admin authority | `AdminConsoleController`, `PluginAdminFacade`, `PluginLifecycleManager` | `AdminConsoleControllerTest`, `HostAuthorizationTest`, `PluginLifecycleManagerTest`, `PluginControlPlaneIntegrationTest` | Proposal v2 sections 12-13; M5 ADR-005/006 |
| Preserve payment ambiguity, immutable original-provider ownership, activation only after confirmed disbursement, and exact decimal transport | Console payment actions and tenant API; BigDecimal JSON string serializer; existing Lending Core/payment gateway workflows | `TenantConsoleControllerTest`, `HostConfigurationTest.serializesMonetaryDecimalsAsExactJsonStrings`, frontend API decimal-preservation test; PostgreSQL payment/recovery tests in `JdbcLendingCoreIntegrationTest`; external-provider gateway tests | M4 ADR-007/008/009; proposal v2 sections 6-8 and 12 |
| Show persisted workflow data without inventing correlation history | `TenantConsoleReadRepository`, `TenantConsoleController.WorkflowView`, Workflow Explorer | `TenantConsoleReadRepositoryTest`, `TenantConsoleControllerTest` | Proposal v2 sections 10 and 12; Console Workflow Explorer documents schema limitation |

## Public Release and Documentation Review (2026-10-29)

| Item | Record |
|---|---|
| Milestone/change | Public README, source/API documentation, license, and repository privacy/artifact audit |
| Reviewer/date | Coding-agent review, 2026-10-29 |
| Relevant requirements and ADRs | Active v2 design baseline; accepted ADR-005, ADR-007 through ADR-014; repository publishing and local-data boundaries |
| Implementation/test/doc references | README; root MIT `LICENSE`; Javadocs in SPI, Lending Core, plugin runtime, risk/payment gateways, host, and demo API; this review record |
| Findings and remediation | No apparent real credentials or tracked generated IDE/build/database artifacts were found. Ignored local IDE/build reports contain workstation paths and must remain untracked. The tracked local temporary path was removed; historical proposal environment notes were generalized. M0 spikes remain intentionally isolated historical validation artifacts. Seeded demo passwords and local database defaults are public synthetic fixtures, not secrets. |
| Quality gate command/result | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" ./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, all 17 modules, 265 tests, 0 failures/errors/skips. Enforcer passed Java 21; PMD had no violations; SpotBugs reported no findings; JaCoCo thresholds passed for production modules. Aggregator and test-only integration module coverage were not applicable. `git diff --check` passed. |
| Frontend/Compose/API evidence | `npm ci` passed; npm reported 3 full-tree vulnerabilities (1 moderate, 2 critical). `npm run build` passed; `npm test` passed 11/11; `npm audit --omit=dev` reported 0 production dependency vulnerabilities. `docker compose config --quiet` passed. The pre-existing Compose stack remained running and reported PostgreSQL, Keycloak, and API healthy; database query returned 2 tenants, 6 memberships, 1 product, 5 active plugins, and 9 bindings; HTTPS borrower route returned HTTP 200. Authenticated E2E was attempted against this already-running stack and failed at the newly added same-tenant borrower list assertion because the running API image predates the current source edits. No Compose restart/reset was performed. |
| Open findings/risks | The full live HTTP E2E has not passed against a freshly rebuilt API containing the current borrower-isolation changes. The attempted E2E changed only synthetic local demo state and left the pre-existing stack running. MIT selected by user; copyright notice uses “FinBridge contributors.” Production threat modeling, production identity/payment integrations, and external legal review of licensing remain outside this documentation pass. |

Publication traceability:

| Requirement | Implementation | Test/evidence | Documentation |
|---|---|---|---|
| External users can identify scope, prerequisites, setup, checks, architecture, and limitations | Root README and getting-started guide | README link/path review; local Java/frontend/Compose checks where runnable | README; `docs/guides/getting-started.md` |
| Reuse and redistribution terms are explicit | Root `LICENSE` and README license section | License text inspection | README; `LICENSE` |
| Public source contracts document provider, workflow, money, payment ownership, and tenant boundaries | Javadocs on SPI, core services/domain, gateways, plugin runtime, host facade, and demo controller | JDK 21 Maven Enforcer/PMD/Javadoc compilation through `clean verify` | SPI, architecture, ADR, and guide links from README |
| No accidental personal paths or tracked local build/IDE/database artifacts | Historical environment notes generalized; local artifacts ignored | `git ls-files`, targeted personal-path scan, `.gitignore` review, `git diff --check` | This review record |

## Plugin Lifecycle Documentation Review (2026-10-29)

| Requirement | Implementation | Test/evidence | Documentation |
|---|---|---|---|
| Explain provider lifecycle, routing handoff, restart behavior, failure semantics, and operational limits to external readers | `PluginRuntime`, `PluginHandle`, `PluginLifecycleManager`, `PluginControlPlaneRepository` | Existing runtime/host lifecycle and PostgreSQL integration tests; source cross-check for stated callback-timeout limitation | `docs/architecture/04-plugin-lifecycle.md`; `docs/guides/plugin-lifecycle-operations.md` |
| Illustrate lifecycle states and the control/data flow | Runtime states, persisted desired state/bindings, gateway routing, invocation leases, immutable payment owner | Mermaid state diagram and flow diagram reviewed against current implementation; `git diff --check` | `docs/architecture/04-plugin-lifecycle.md` |

## Feedback Remediation and Published Release Evidence (2026-10-30)

| Item | Record |
|---|---|
| Source identity | Published commit [`30d28e15034a580db8ec10661d739037378aa45f`](https://github.com/chen3862000-coder/finbridge/commit/30d28e15034a580db8ec10661d739037378aa45f) on `main`. GitHub Actions [run 41049982380](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380) ran against this exact SHA; all three jobs (`java`, `frontend`, `compose-e2e`) passed. |
| Lifecycle callback implementation/evidence | `PluginLifecycleCallbackExecutor` uses separate fixed-size, no-queue daemon pools for init/health and close, callback deadlines, fast saturation rejection, TCCL setup/restoration, and timeout quarantine. Non-cooperative callbacks may continue after timeout; interruption is not forcible termination. Runtime module `./mvnw --batch-mode --no-transfer-progress -f finbridge-plugin-runtime/pom.xml clean verify` passed: 60 tests, 0 failures/errors/skips; Enforcer, PMD, SpotBugs, and all JaCoCo class/method/bundle checks passed. |
| Full Java gate | Published commit `30d28e15034a580db8ec10661d739037378aa45f` passed CI job [`java`](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380/job/124043348577): all 17 modules, 301 tests, 0 failures/errors/skips. Enforcer passed Java 21; PMD had no violations; SpotBugs found 0 findings; applicable JaCoCo thresholds passed for 15 production modules. Root aggregator had no execution data and test-only integration module has no production classes. |
| Frontend gates | Published commit passed CI job [`frontend`](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380/job/124043349073): `npm ci`, build, 11 unit tests, 3 accessibility/viewport checks, and production dependency audit. Local full-tree `npm audit` and `npm audit --omit=dev` both reported 0 vulnerabilities. Accessibility checks use axe-core WCAG 2.1 A/AA tags and jsdom keyboard/viewport structure; these are not manual assistive-technology certification. |
| Compose source build/network | `docker compose config --quiet` passed. A first `scripts/refresh-local-demo.sh --confirm-data-reset` run backed up prior synthetic data and removed/recreated the local volumes, but the Dockerfile build exceeded the 30-minute tool limit while pulling `maven:3.9.11-eclipse-temurin-21`. A retry failed on Docker Hub with `DeadlineExceeded`. The retained backup is ignored under `local-backups/`. |
| Clean Compose and authenticated E2E | Published commit passed CI job [`compose-e2e`](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380/job/124043349168), which built and started a clean Compose stack, recorded commit identity, service health and synthetic seed counts, requested the borrower route, and passed the authenticated HTTP suite including AlphaPay v1->v2->v1 rollback. This clean CI run supersedes the earlier local Docker Hub timeout/no-build workaround as release evidence. |
| Current verdict and remaining release gaps | All three jobs in [GitHub Actions run 41049982380](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380) passed for published commit `30d28e15034a580db8ec10661d739037378aa45f`. Screenshots cover borrower applications, workflow desktop/mobile, payment owner, plugin registry/audit, loans, and routing desktop/mobile. Mobile navigation scrolls horizontally and route rows scroll vertically; browser checks found no page-level horizontal overflow. Dedicated rollback confirmation UI is not present; v1/v2 rollback is evidenced in plugin audit screenshots. Workflow screenshots are borrower-role views because admin workflow access is denied. Automated accessibility evidence does not replace manual assistive-technology review. Production security/identity review remains out of scope. The previous local Docker workaround and registry timeout are historical troubleshooting notes only. |

Current requirement traceability:

| Requirement | Implementation | Test/evidence | Documentation |
|---|---|---|---|
| Bound lifecycle callback execution and fail closed on timeout/saturation | `PluginLifecycleCallbackExecutor`, `PluginLifecycleResources`, `PluginRuntime`, `PluginDiscovery` | Real external JAR callback tests; runtime module clean verify (60 tests) | `04-plugin-lifecycle.md`; `docs/security/threat-model.md` |
| Keep CI enforcing Java, frontend, audit, Compose and authenticated E2E gates | `.github/workflows/quality.yml` jobs `java`, `frontend`, `compose-e2e` | Workflow executes on push/PR; local equivalents recorded above | `docs/quality/quality-gates.md`; this record |
| Remove development dependency advisories | `frontend/package.json`, lockfile (Vitest 5.0.5) | `npm audit` and `npm audit --omit=dev`: 0 vulnerabilities | Case study; frontend checks |
| Document the local threat boundary and correct loader behavior | CSP/Permissions Policy, default Spring forwarded-header handling, `docs/security/threat-model.md`, class-loader wording | Current live E2E passes behind local Nginx; config inspection | `03-classloader-isolation.md`, `13-production-evolution.md`, README |
| Preserve responsive/a11y keyboard and semantic behavior | Console skip link, focus ring, nav keyboard handling, modal focus management, semantic headers | 3 axe/jsdom viewport/keyboard checks; no visual/browser screenshot evidence | `frontend/src/Console.a11y.test.tsx`; explicit screenshot gap above |
