package io.github.finbridge.plugins.fraudsignalz;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.risk.FraudSignalValue;
import io.github.finbridge.spi.risk.RiskDataProvider;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.util.Set;

public final class FraudSignalZProvider implements RiskDataProvider {
    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("fraudsignalz",
            "FRAUDSIGNALZ", "1.0.0", "1.0", PluginType.RISK_DATA,
            Set.of(Capability.FRAUD_SIGNAL), "FraudSignalZ", "Synthetic fraud signal fixture");

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RiskDataType supportedType() {
        return RiskDataType.FRAUD_SIGNAL;
    }

    @Override
    public RiskDataResult query(RiskDataRequest request, OperationContext context) {
        FraudSignalValue.Severity severity = request.syntheticApplicantId().contains("critical-fraud")
                ? FraudSignalValue.Severity.CRITICAL : FraudSignalValue.Severity.NONE;
        return RiskDataResult.available(new FraudSignalValue(severity,
                severity == FraudSignalValue.Severity.CRITICAL ? "SYNTHETIC_CRITICAL" : "SYNTHETIC_CLEAR"));
    }
}
