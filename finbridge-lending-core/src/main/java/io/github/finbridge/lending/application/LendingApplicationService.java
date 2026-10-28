package io.github.finbridge.lending.application;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.persistence.LoanApplicationRepository;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LendingApplicationService {
    private final ApplicationRepositoryBoundary applications;
    private final LoanProductLookupBoundary products;
    private final Clock clock;

    public LendingApplicationService(LoanApplicationRepository applications,
                                     LoanProductRepository products, Clock clock) {
        this.applications = new ApplicationRepositoryBoundary(applications);
        this.products = new LoanProductLookupBoundary(products);
        this.clock = clock;
    }

    public LoanApplication createApplication(CreateLoanApplicationCommand command) {
        Objects.requireNonNull(command, "command");
        LoanProduct product = products.findActive(command.productCode())
                .orElseThrow(() -> new LendingCoreException("Unknown or inactive loan product"));
        product.validate(command.requestedAmount(), command.termMonths(), command.currency());
        Instant now = clock.instant();
        LoanApplication application = new LoanApplication(UUID.randomUUID(), command.tenantId(),
                command.applicantId(), product.productCode(), product.version(),
                command.requestedAmount(), command.currency(), command.termMonths(),
                ApplicationStatus.SUBMITTED, now, now, 0);
        return applications.insert(application, command.idempotencyKey(), fingerprint(command));
    }

    public LoanApplication getApplication(UUID tenantId, UUID applicationId) {
        return applications.find(tenantId, applicationId)
                .orElseThrow(() -> new LendingCoreException("Loan application was not found"));
    }

    public List<LoanApplication> listApplications(UUID tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        return applications.findRecent(tenantId, limit);
    }

    public LoanApplication startRiskEvaluation(UUID tenantId, UUID applicationId) {
        return transition(tenantId, applicationId, ApplicationStatus.RISK_EVALUATING);
    }

    public LoanApplication recordRiskDecision(UUID tenantId, UUID applicationId,
                                              AssessmentDecision decision) {
        Objects.requireNonNull(decision, "decision");
        ApplicationStatus target = switch (decision) {
            case APPROVE -> ApplicationStatus.APPROVED;
            case REJECT -> ApplicationStatus.REJECTED;
            case REVIEW -> ApplicationStatus.MANUAL_REVIEW;
        };
        return transition(tenantId, applicationId, target);
    }

    public LoanApplication markOriginationInProgress(UUID tenantId, UUID applicationId) {
        return transition(tenantId, applicationId, ApplicationStatus.ORIGINATION_IN_PROGRESS);
    }

    public LoanApplication markOriginated(UUID tenantId, UUID applicationId) {
        return transition(tenantId, applicationId, ApplicationStatus.ORIGINATED);
    }

    public LoanApplication cancel(UUID tenantId, UUID applicationId) {
        return transition(tenantId, applicationId, ApplicationStatus.CANCELLED);
    }

    private LoanApplication transition(UUID tenantId, UUID applicationId,
                                       ApplicationStatus nextStatus) {
        LoanApplication current = getApplication(tenantId, applicationId);
        LoanApplication updated = current.transitionTo(nextStatus, clock.instant());
        return applications.updateStatus(updated, current.version());
    }

    private static String fingerprint(CreateLoanApplicationCommand command) {
        String canonical = String.join("|", command.tenantId().toString(), command.applicantId(),
                command.productCode(), command.requestedAmount().toPlainString(),
                command.currency().getCurrencyCode(), Integer.toString(command.termMonths()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class ApplicationRepositoryBoundary
            implements LoanApplicationRepository {
        private final LoanApplicationRepository delegate;

        private ApplicationRepositoryBoundary(LoanApplicationRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public LoanApplication insert(LoanApplication application, String idempotencyKey,
                                      String requestFingerprint) {
            return delegate.insert(application, idempotencyKey, requestFingerprint);
        }

        @Override
        public Optional<LoanApplication> find(UUID tenantId, UUID applicationId) {
            return delegate.find(tenantId, applicationId);
        }

        @Override
        public List<LoanApplication> findRecent(UUID tenantId, int limit) {
            return delegate.findRecent(tenantId, limit);
        }

        @Override
        public LoanApplication updateStatus(LoanApplication application, long expectedVersion) {
            return delegate.updateStatus(application, expectedVersion);
        }
    }

    private static final class LoanProductLookupBoundary {
        private final LoanProductRepository delegate;

        private LoanProductLookupBoundary(LoanProductRepository delegate) {
            this.delegate = delegate;
        }

        private Optional<LoanProduct> findActive(String productCode) {
            return delegate.findActive(productCode);
        }
    }
}
