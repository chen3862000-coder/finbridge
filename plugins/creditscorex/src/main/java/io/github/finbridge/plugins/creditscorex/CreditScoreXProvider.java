package io.github.finbridge.plugins.creditscorex;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.RiskDataProvider;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.util.Set;

public final class CreditScoreXProvider implements RiskDataProvider {
    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("creditscorex",
            "CREDITSCOREX", "1.0.0", "1.0", PluginType.RISK_DATA,
            Set.of(Capability.CREDIT_SCORE), "CreditScoreX", "Synthetic credit score fixture");

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RiskDataType supportedType() {
        return RiskDataType.CREDIT_SCORE;
    }

    @Override
    public RiskDataResult query(RiskDataRequest request, OperationContext context) {
        String applicant = request.syntheticApplicantId();
        int score = applicant.contains("low-score") ? 550
                : applicant.contains("review-score") || applicant.contains("review") ? 640 : 720;
        return RiskDataResult.available(new CreditScoreValue(score));
    }
}
