package io.github.finbridge.host;

import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PluginRecoveryRunner {
    private final RiskAssessmentService risk;
    private final DisbursementService disbursements;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Optional<String> lastFailure = Optional.empty();

    public PluginRecoveryRunner(RiskAssessmentService risk, DisbursementService disbursements) {
        this.risk = Objects.requireNonNull(risk, "risk");
        this.disbursements = Objects.requireNonNull(disbursements, "disbursements");
    }

    public void recoverAtStartup() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            risk.recoverInterruptedWorkflows();
            disbursements.recoverUnresolvedPayments();
        } catch (RuntimeException failed) {
            lastFailure = Optional.of(failed.getClass().getSimpleName());
            started.set(false);
            return;
        }
        lastFailure = Optional.empty();
    }

    public boolean recovered() {
        return started.get() && lastFailure.isEmpty();
    }

    public Optional<String> failureDiagnostic() {
        return lastFailure;
    }
}
