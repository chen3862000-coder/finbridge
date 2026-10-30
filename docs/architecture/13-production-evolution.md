# Production Evolution

## Implemented Baseline

FinBridge is a Java 21 modular monolith with a PostgreSQL-backed synthetic lending workflow, typed risk/payment SPI, external provider JARs, tenant capability routing, lifecycle controls, and an authenticated Keycloak-backed local borrower demo. The host does not put concrete provider implementations on its application classpath. Plugin lifecycle init/health/close callbacks have bounded worker execution and deadlines; timed-out providers are quarantined. Uncooperative in-process code cannot be forcibly terminated. The local borrower journey is exercised over HTTP through the same-origin HTTPS proxy.

The root `clean verify` is the recorded Java quality gate. Local Compose and live E2E evidence is documented in `docs/quality/architecture-review-checklist.md`. The evidence demonstrates these configured scenarios only; it does not establish production readiness, financial correctness, underwriting validity, or payment settlement guarantees.

## Explicitly Not Implemented / Not Claimed

- Multi-host orchestration, distributed leases/fencing, horizontal session sharing, or clustered runtime state.
- Untrusted plugin sandboxing, process isolation, signed artifacts, arbitrary upload, or a software supply-chain attestation process.
- Production OIDC selection, token/key operations, enterprise identity federation, secrets management, or production TLS operations. The local demo has a concise threat model in `docs/security/threat-model.md`; this is not a production threat assessment.
- Validated credit policy, real customer/provider data, real payment effects, accounting ledger, regulatory compliance, or production lending decisions.
- Automated retry of ambiguous payment effects, automatic provider failover, or retry after terminal payment failure.

## Evolution Gates

Each item requires an approved design change and evidence before implementation. Preserve the existing invariants: server-derived tenant identity, provider calls outside local transactions, immutable historical payment owner, query-only ambiguity recovery, exact decimal money, and loan activation only after persisted confirmed disbursement.

## Evidence and Decision References

- Active baseline and milestone gates: `docs/architecture/m0-proposal-v2.md`
- Implemented local identity boundary and caveats: `docs/adr/ADR-014-authenticated-borrower-demo-api.md`
- Lifecycle, payment ownership, workflow recovery: ADR-006, ADR-007, ADR-008, ADR-009, ADR-013
- Executable architecture/package checks: `integration-tests/src/test/java/io/github/finbridge/integration/ArchitectureBoundaryTest.java` and `HostPackagingTest.java`
- Quality and remaining M7 work: `docs/quality/architecture-review-checklist.md`
