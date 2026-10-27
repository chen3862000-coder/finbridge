package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import org.junit.jupiter.api.Test;

class PluginRecoveryRunnerTest {
    @Test
    void recoveryRunsRiskAndPaymentRecoveryOnlyOnce() {
        RiskAssessmentService risk = mock(RiskAssessmentService.class);
        DisbursementService payments = mock(DisbursementService.class);
        PluginRecoveryRunner runner = new PluginRecoveryRunner(risk, payments);

        runner.recoverAtStartup();
        runner.recoverAtStartup();

        assertTrue(runner.recovered());
        assertTrue(runner.failureDiagnostic().isEmpty());
        verify(risk).recoverInterruptedWorkflows();
        verify(payments).recoverUnresolvedPayments();
        verifyNoMoreInteractions(risk, payments);
    }

    @Test
    void recoveryFailureIsObservableAndCanBeRetried() {
        RiskAssessmentService risk = mock(RiskAssessmentService.class);
        DisbursementService payments = mock(DisbursementService.class);
        when(risk.recoverInterruptedWorkflows())
                .thenThrow(new IllegalStateException("synthetic recovery failure"))
                .thenReturn(new RiskAssessmentService.WorkflowRecoverySummary(0, 0, 0));
        PluginRecoveryRunner runner = new PluginRecoveryRunner(risk, payments);

        runner.recoverAtStartup();
        assertFalse(runner.recovered());
        assertEquals("IllegalStateException", runner.failureDiagnostic().orElseThrow());

        runner.recoverAtStartup();
        assertTrue(runner.recovered());
        assertTrue(runner.failureDiagnostic().isEmpty());
        verify(risk, org.mockito.Mockito.times(2)).recoverInterruptedWorkflows();
        verify(payments).recoverUnresolvedPayments();
    }
}
