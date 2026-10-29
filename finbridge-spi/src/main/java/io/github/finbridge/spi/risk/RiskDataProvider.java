package io.github.finbridge.spi.risk;

import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.OperationContext;

/** Supplies one typed risk-data capability; lending decisions remain owned by the core. */
public interface RiskDataProvider extends FinBridgePlugin {
    /** Identifies the single risk-data type this provider returns. */
    RiskDataType supportedType();

    /** Returns typed evidence or an explicit unavailable/invalid result, never an approval decision. */
    RiskDataResult query(RiskDataRequest request, OperationContext context);
}
