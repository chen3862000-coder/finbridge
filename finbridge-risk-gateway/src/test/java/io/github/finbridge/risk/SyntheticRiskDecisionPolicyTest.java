package io.github.finbridge.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.FraudSignalValue;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticRiskDecisionPolicyTest {
    private final SyntheticRiskDecisionPolicy policy = new SyntheticRiskDecisionPolicy();

    @Test
    void appliesScoreBoundaries() {
        assertEquals(RiskDecision.REJECT, policy.evaluate(data(599, false, null)).decision());
        assertEquals(RiskDecision.REVIEW, policy.evaluate(data(600, false, null)).decision());
        assertEquals(RiskDecision.REVIEW, policy.evaluate(data(679, false, null)).decision());
        assertEquals(RiskDecision.APPROVE, policy.evaluate(data(680, false, null)).decision());
    }

    @Test
    void blacklistAndCriticalFraudRejectBeforeScoreBand() {
        assertEquals(RiskDecision.REJECT, policy.evaluate(data(720, true, null)).decision());
        assertEquals(RiskDecision.REJECT, policy.evaluate(data(720, false,
                FraudSignalValue.Severity.CRITICAL)).decision());
    }

    @Test
    void mandatoryMissingRequestsReviewAndOptionalFraudMayBeUnavailable() {
        Map<RiskDataType, RiskDataResult> missing = new EnumMap<>(RiskDataType.class);
        missing.put(RiskDataType.CREDIT_SCORE,
                RiskDataResult.unavailable(RiskDataType.CREDIT_SCORE, "TIMEOUT"));
        missing.put(RiskDataType.BLACKLIST,
                RiskDataResult.available(new BlacklistValue(false)));
        missing.put(RiskDataType.FRAUD_SIGNAL,
                RiskDataResult.unavailable(RiskDataType.FRAUD_SIGNAL, "TIMEOUT"));
        assertEquals(RiskDecision.REVIEW, policy.evaluate(missing).decision());

        var approved = policy.evaluate(data(720, false, null));
        assertEquals(RiskDecision.APPROVE, approved.decision());
        assertEquals("OPTIONAL_FRAUD_DATA_UNAVAILABLE", approved.reasonCodes().getLast());
        assertEquals(SyntheticRiskDecisionPolicy.VERSION, approved.policyVersion());
    }

    @Test
    void invalidMandatorySignalRequestsReviewWhileInvalidOptionalSignalDoesNotBlock() {
        Map<RiskDataType, RiskDataResult> results = new EnumMap<>(RiskDataType.class);
        results.put(RiskDataType.CREDIT_SCORE,
                RiskDataResult.available(new CreditScoreValue(720)));
        results.put(RiskDataType.BLACKLIST,
                RiskDataResult.invalid(RiskDataType.BLACKLIST, "BAD_RESPONSE"));
        results.put(RiskDataType.FRAUD_SIGNAL,
                RiskDataResult.invalid(RiskDataType.FRAUD_SIGNAL, "BAD_RESPONSE"));
        assertEquals(RiskDecision.REVIEW, policy.evaluate(results).decision());
        results.put(RiskDataType.BLACKLIST,
                RiskDataResult.available(new BlacklistValue(false)));
        var optionalInvalid = policy.evaluate(results);
        assertEquals(RiskDecision.APPROVE, optionalInvalid.decision());
        assertEquals("OPTIONAL_FRAUD_DATA_UNAVAILABLE", optionalInvalid.reasonCodes().getLast());
    }

    private static Map<RiskDataType, RiskDataResult> data(int score, boolean blacklist,
            FraudSignalValue.Severity fraud) {
        Map<RiskDataType, RiskDataResult> results = new EnumMap<>(RiskDataType.class);
        results.put(RiskDataType.CREDIT_SCORE,
                RiskDataResult.available(new CreditScoreValue(score)));
        results.put(RiskDataType.BLACKLIST,
                RiskDataResult.available(new BlacklistValue(blacklist)));
        results.put(RiskDataType.FRAUD_SIGNAL, fraud == null
                ? RiskDataResult.unavailable(RiskDataType.FRAUD_SIGNAL, "OPTIONAL_TIMEOUT")
                : RiskDataResult.available(new FraudSignalValue(fraud, "SYNTHETIC")));
        return results;
    }
}
