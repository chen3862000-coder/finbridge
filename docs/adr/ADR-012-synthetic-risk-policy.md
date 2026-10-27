# ADR-012: Synthetic Lending Risk Policy

- **Status:** Accepted by user for M3 on 2026-10-26
- **Date:** 2026-10-26

## Context

M3 needs a reproducible decision to demonstrate that Lending Core owns approval and provider plugins only supply normalized evidence. The prompt left the exact score thresholds and mandatory signals open. The decision must remain synthetic and must fail safely on missing mandatory information.

## Decision

Use policy version `demo-risk-policy-1`. Credit score and blacklist are mandatory; fraud signal is optional. A blacklist hit or CRITICAL fraud signal yields REJECT. If either mandatory datum is unavailable or invalid, yield REVIEW. Otherwise, score below 600 yields REJECT; 600 through 679 yields REVIEW; 680 or greater yields APPROVE unless a rejection condition applies. A provider failure is unavailable information, not a negative risk fact. Persist normalized facts, reason codes, provider version references, and policy version. Risk provider code does not decide.

## Alternatives

- Treat missing data as favorable/clear: risks false approval and loses fail-safe semantics.
- Make every risk capability mandatory: exposes decisions to optional fraud-provider outages and was not selected for this demo.
- Delegate the decision to a provider: couples business policy to provider response formats and contradicts the Lending Core boundary.
- Use different score thresholds: possible but needs another explicit policy version and user-approved decision.

## Rationale

The rule is small, deterministic, auditable, easy to reproduce in a demo, and makes missing mandatory information explicit. Keeping fraud optional demonstrates partial-data policy without silently treating missing credit/blacklist evidence as safe.

## Consequences

- Thresholds are illustrative synthetic values, not a credit model, offer, or underwriting recommendation.
- Policy code and tests must use the policy version as a stable identity.
- Tests must cover threshold boundaries, blacklist/fraud precedence, mandatory missing/invalid data, and provider references.

## Risks

- Simple score bands are not statistically validated and must never be presented as real lending risk.
- A real product would require governance, explainability, legal/regulatory analysis, fairness evaluation, and calibrated data.

## Revisit Conditions

Revisit before any real data/provider use, policy claims beyond the synthetic demo, or a change to mandatory capability requirements or score bands.
