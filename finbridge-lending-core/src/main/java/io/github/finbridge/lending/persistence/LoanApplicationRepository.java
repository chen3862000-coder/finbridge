package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.LoanApplication;
public interface LoanApplicationRepository extends LoanApplicationLookup {
    LoanApplication insert(LoanApplication application, String idempotencyKey,
                           String requestFingerprint);

    LoanApplication updateStatus(LoanApplication application, long expectedVersion);
}
