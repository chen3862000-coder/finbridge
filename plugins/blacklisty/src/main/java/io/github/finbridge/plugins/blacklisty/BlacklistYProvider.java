package io.github.finbridge.plugins.blacklisty;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.RiskDataProvider;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.util.Set;

public final class BlacklistYProvider implements RiskDataProvider {
    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("blacklisty",
            "BLACKLISTY", "1.0.0", "1.0", PluginType.RISK_DATA,
            Set.of(Capability.BLACKLIST), "BlacklistY", "Synthetic blacklist fixture");

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RiskDataType supportedType() {
        return RiskDataType.BLACKLIST;
    }

    @Override
    public RiskDataResult query(RiskDataRequest request, OperationContext context) {
        return RiskDataResult.available(new BlacklistValue(
                request.syntheticApplicantId().contains("blacklisted")));
    }
}
