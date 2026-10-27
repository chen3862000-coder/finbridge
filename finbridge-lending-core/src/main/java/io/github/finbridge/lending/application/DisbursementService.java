package io.github.finbridge.lending.application;

import io.github.finbridge.lending.domain.PaymentTransaction;
import io.github.finbridge.lending.domain.PaymentTransactionStatus;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.persistence.OriginationWorkflowState;
import io.github.finbridge.payment.PaymentGateway;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.PaymentStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DisbursementService {
    private static final String OPERATION = "DISBURSE";
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(7);
    private final LendingAggregateRepository aggregates;
    private final LoanOriginationService origination;
    private final PaymentGateway gateway;
    private final PluginRuntime runtime;
    private final Clock clock;

    public DisbursementService(LendingAggregateRepository aggregates,
            LoanOriginationService origination, PaymentGateway gateway,
            PluginRuntime runtime, Clock clock) {
        this.aggregates = aggregates;
        this.origination = origination;
        this.gateway = gateway;
        this.runtime = runtime;
        this.clock = clock;
    }

    public DisbursementClaim dispatch(UUID tenantId, UUID loanId, String idempotencyKey,
            String correlationId) {
        requireRequest(tenantId, loanId, idempotencyKey, correlationId);
        var loan = origination.getLoan(tenantId, loanId);
        String fingerprint = fingerprint(tenantId, loanId, loan.principal().toPlainString(),
                loan.currency().getCurrencyCode());
        PluginKey owner = aggregates.findPaymentByIdempotencyKey(tenantId, idempotencyKey)
                .map(PaymentTransaction::owner)
                .orElseGet(() -> selectedOwner(tenantId));
        Instant now = clock.instant();
        DisbursementClaim claim = aggregates.claimDisbursement(tenantId, loanId, owner,
                UUID.randomUUID(), idempotencyKey, fingerprint, UUID.randomUUID(), now);
        if (!claim.dispatchClaimed()) {
            return claim;
        }

        PaymentGateway.DisbursementOutcome outcome = gateway.disburse(runtime, owner,
                new DisbursementRequest(claim.transaction().paymentTransactionId(), tenantId, loanId,
                        claim.transaction().amount(), claim.transaction().currency(), idempotencyKey),
                context(correlationId, now));
        return persistOutcome(tenantId, claim, outcome, now);
    }

    public DisbursementClaim reconcile(UUID tenantId, UUID loanId, String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loanId, "loanId");
        requireCorrelation(correlationId);
        DisbursementClaim claim = origination.getDisbursement(tenantId, loanId);
        PaymentTransaction transaction = claim.transaction();
        if (transaction.status() == PaymentTransactionStatus.SUCCEEDED) {
            return aggregates.recordDisbursementOutcome(tenantId, transaction.paymentTransactionId(),
                    transaction.dispatchAttemptId(), PaymentStatus.SUCCEEDED,
                    transaction.providerTransactionId(), transaction.diagnosticCode(),
                    transaction.updatedAt(), LocalDate.ofInstant(transaction.updatedAt(), ZoneOffset.UTC),
                    clock.instant());
        }
        if (transaction.status() == PaymentTransactionStatus.FAILED) {
            if (claim.workflow().state() == OriginationWorkflowState.PAYMENT_FAILED) {
                return claim;
            }
            return aggregates.recordDisbursementOutcome(tenantId, transaction.paymentTransactionId(),
                    transaction.dispatchAttemptId(), PaymentStatus.FAILED,
                    transaction.providerTransactionId(), transaction.diagnosticCode(), null, null,
                    clock.instant());
        }
        Instant now = clock.instant();
        if (transaction.status() == PaymentTransactionStatus.SUBMISSION_IN_PROGRESS) {
            claim = aggregates.markInterruptedDispatchUnknown(tenantId,
                    transaction.paymentTransactionId(), now);
            transaction = claim.transaction();
        }
        PaymentGateway.DisbursementOutcome outcome = gateway.reconcile(runtime, transaction.owner(),
                transaction.paymentTransactionId(), transaction.idempotencyKey(),
                transaction.providerTransactionId(), context(correlationId, now));
        return persistOutcome(tenantId, claim, outcome, now);
    }

    public DisbursementClaim getStatus(UUID tenantId, UUID loanId) {
        return origination.getDisbursement(tenantId, loanId);
    }

    private DisbursementClaim persistOutcome(UUID tenantId, DisbursementClaim claim,
            PaymentGateway.DisbursementOutcome outcome, Instant now) {
        Instant confirmedAt = outcome.status() == PaymentStatus.SUCCEEDED ? now : null;
        LocalDate disbursementDate = confirmedAt == null ? null
                : LocalDate.ofInstant(confirmedAt, ZoneOffset.UTC);
        return aggregates.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                outcome.status(), outcome.providerTransactionId(), outcome.diagnosticCode(),
                confirmedAt, disbursementDate, now);
    }

    private PluginKey selectedOwner(UUID tenantId) {
        try {
            return runtime.binding(tenantId, Capability.DISBURSE).pluginKey();
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            throw new LendingCoreException("No disbursement provider is bound", unavailable);
        }
    }

    private static OperationContext context(String correlationId, Instant now) {
        return new OperationContext(UUID.randomUUID(), correlationId, now.plus(OPERATION_TIMEOUT));
    }

    private static void requireRequest(UUID tenantId, UUID loanId, String key, String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loanId, "loanId");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        requireCorrelation(correlationId);
    }

    private static void requireCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }

    private static String fingerprint(UUID tenantId, UUID loanId, String amount, String currency) {
        return fingerprint(tenantId, loanId, amount, currency, "SHA-256");
    }

    static String fingerprint(UUID tenantId, UUID loanId, String amount, String currency,
            String algorithm) {
        String canonical = OPERATION + "|" + tenantId + "|" + loanId + "|" + amount + "|" + currency;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required fingerprint algorithm is unavailable", unavailable);
        }
    }
}
