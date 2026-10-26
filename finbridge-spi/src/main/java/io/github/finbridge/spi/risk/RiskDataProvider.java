package io.github.finbridge.spi.risk;

import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.OperationContext;

public interface RiskDataProvider extends FinBridgePlugin {
    RiskDataType supportedType();

    RiskDataResult query(RiskDataRequest request, OperationContext context);
}
