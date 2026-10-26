package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.LoanApplication;
import java.util.Optional;
import java.util.UUID;

public interface LoanApplicationLookup {
    Optional<LoanApplication> find(UUID tenantId, UUID applicationId);
}
