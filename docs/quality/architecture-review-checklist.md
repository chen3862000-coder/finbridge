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
| Quality gate command/result | `JAVA_HOME="/Users/spiderck/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home" PATH="/Users/spiderck/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home/bin:$PATH" ./mvnw --batch-mode --no-transfer-progress clean verify` -> BUILD SUCCESS, full 14-module reactor. 106 tests passed, 0 failures/errors/skips. SpotBugs reported zero findings; PMD had no violations; JaCoCo bundle/class/method checks met configured thresholds in all production modules. PostgreSQL Testcontainers and external plugin JAR tests ran. |
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
| Quality gate command/result | `env JAVA_HOME="/Users/spiderck/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home" PATH="/Users/spiderck/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home/bin:/usr/bin:/bin:/usr/sbin:/sbin" ./mvnw --batch-mode --no-transfer-progress clean verify` passed for the full 16-project reactor. Surefire: 148 tests passed, 0 failures/errors/skips, including 37 PostgreSQL integration tests and external AlphaPay/BetaPay artifact tests. SpotBugs: 0 findings; PMD: clean; all production JaCoCo checks met. Test-only integration module ran its tests; JaCoCo is not applicable because it has no production classes. |
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
