# Production Evolution and Limits

FinBridge currently demonstrates a single-host Java modular monolith with synthetic lending flows, external risk/payment JARs, PostgreSQL, tenant capability routing, lifecycle controls, and a local authenticated Console. The [platform specification](01-platform-spec.md) summarizes current scope; published implementation and CI evidence are linked in the [architecture review checklist](../quality/architecture-review-checklist.md).

## Explicitly Not Implemented

- Multi-host orchestration, distributed leases/fencing, shared sessions, or clustered runtime state.
- Untrusted plugin sandboxing/process isolation, signed artifacts, arbitrary upload, or supply-chain attestation.
- Production OIDC/identity federation, key and secret operations, production TLS operations, or production threat certification.
- Real credit policy, real customer/provider data, real payments/settlement, accounting ledger, or regulatory compliance.
- Automatic retry or provider failover for ambiguous or definitive payment failures.

Potential future work includes process isolation for untrusted integrations, production identity and secret management, distributed coordination, audit/operations governance, and more advanced workflow tooling if deployment needs warrant it. Each requires a separate approved design, threat assessment, implementation, and evidence. These possibilities are not commitments or current guarantees.
