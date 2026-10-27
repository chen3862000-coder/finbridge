package io.github.finbridge.risk;

import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.FraudSignalValue;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.util.ArrayList;
import java.util.Map;

public final class SyntheticRiskDecisionPolicy {
    public static final String VERSION = "demo-risk-policy-1";
    public static final int REJECT_SCORE_BELOW = 600;
    public static final int APPROVE_SCORE_FROM = 680;

    public Decision evaluate(Map<RiskDataType, RiskDataResult> outcomes) {
        java.util.List<String> reasons = new ArrayList<>();
        RiskDataResult blacklistResult = outcomes.get(RiskDataType.BLACKLIST);
        RiskDataResult scoreResult = outcomes.get(RiskDataType.CREDIT_SCORE);
        RiskDataResult fraudResult = outcomes.get(RiskDataType.FRAUD_SIGNAL);
        return decide(blacklistResult, scoreResult, fraudResult, reasons);
    }

    private Decision decide(RiskDataResult blacklistResult, RiskDataResult scoreResult,
                            RiskDataResult fraudResult, java.util.List<String> reasons) {
        if (blacklistHit(blacklistResult)) {
            reasons.add("BLACKLIST_HIT");
            return decision(RiskDecision.REJECT, reasons, blacklistResult, scoreResult, fraudResult);
        }
        if (criticalFraud(fraudResult)) {
            reasons.add("CRITICAL_FRAUD_SIGNAL");
            return decision(RiskDecision.REJECT, reasons, blacklistResult, scoreResult, fraudResult);
        }
        if (!mandatoryDataAvailable(blacklistResult, scoreResult)) {
            reasons.add("MANDATORY_RISK_DATA_UNAVAILABLE");
            return decision(RiskDecision.REVIEW, reasons, blacklistResult, scoreResult,
                    matching(fraudResult, RiskDataType.FRAUD_SIGNAL) ? fraudResult : null);
        }

        CreditScoreValue score = (CreditScoreValue) scoreResult.value();
        return decideByScore(score, blacklistResult, scoreResult, fraudResult, reasons);
    }

    private Decision decideByScore(CreditScoreValue score, RiskDataResult blacklistResult,
            RiskDataResult scoreResult, RiskDataResult fraudResult, java.util.List<String> reasons) {
        if (score.score() < REJECT_SCORE_BELOW) {
            reasons.add("CREDIT_SCORE_BELOW_MINIMUM");
            return decision(RiskDecision.REJECT, reasons, blacklistResult, scoreResult, fraudResult);
        }
        if (score.score() < APPROVE_SCORE_FROM) {
            reasons.add("CREDIT_SCORE_REVIEW_BAND");
            return decision(RiskDecision.REVIEW, reasons, blacklistResult, scoreResult, fraudResult);
        }
        reasons.add("MANDATORY_RISK_DATA_CLEAR");
        if (!matching(fraudResult, RiskDataType.FRAUD_SIGNAL) || !available(fraudResult)) {
            reasons.add("OPTIONAL_FRAUD_DATA_UNAVAILABLE");
        }
        return decision(RiskDecision.APPROVE, reasons, blacklistResult, scoreResult, fraudResult);
    }

    private static boolean blacklistHit(RiskDataResult result) {
        return matching(result, RiskDataType.BLACKLIST) && available(result)
                && ((BlacklistValue) result.value()).hit();
    }

    private static boolean criticalFraud(RiskDataResult result) {
        return matching(result, RiskDataType.FRAUD_SIGNAL) && isCritical(result);
    }

    private static boolean mandatoryDataAvailable(RiskDataResult blacklist, RiskDataResult score) {
        return available(blacklist) && blacklist.dataType() == RiskDataType.BLACKLIST
                && available(score) && score.dataType() == RiskDataType.CREDIT_SCORE;
    }

    private static Decision decision(RiskDecision decision, java.util.List<String> reasons,
                                     RiskDataResult blacklist, RiskDataResult score,
                                     RiskDataResult fraud) {
        Integer creditScore = available(score) ? ((CreditScoreValue) score.value()).score() : null;
        Boolean blacklistHit = available(blacklist) ? ((BlacklistValue) blacklist.value()).hit() : null;
        String fraudSignal = available(fraud)
                ? ((FraudSignalValue) fraud.value()).severity().name()
                : null;
        return new Decision(decision, reasons, creditScore, blacklistHit, fraudSignal, VERSION);
    }

    private static boolean isCritical(RiskDataResult result) {
        return available(result)
                && ((FraudSignalValue) result.value()).severity() == FraudSignalValue.Severity.CRITICAL;
    }

    private static boolean matching(RiskDataResult result, RiskDataType type) {
        return result != null && result.dataType() == type;
    }

    private static boolean available(RiskDataResult result) {
        return result != null && result.status() == RiskDataResult.Status.AVAILABLE;
    }

    public record Decision(RiskDecision decision, java.util.List<String> reasonCodes,
                           Integer creditScore, Boolean blacklistHit, String fraudSignal,
                           String policyVersion) {
        public Decision {
            reasonCodes = java.util.List.copyOf(reasonCodes);
        }
    }
}
