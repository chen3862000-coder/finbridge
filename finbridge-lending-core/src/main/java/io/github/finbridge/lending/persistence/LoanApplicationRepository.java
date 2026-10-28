package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.LoanApplication;
import java.util.List;
import java.util.UUID;

public interface LoanApplicationRepository extends LoanApplicationLookup {
    LoanApplication insert(LoanApplication application, String idempotencyKey,
                           String requestFingerprint);

    LoanApplication updateStatus(LoanApplication application, long expectedVersion);

    List<LoanApplication> findRecent(UUID tenantId, int limit);
}
