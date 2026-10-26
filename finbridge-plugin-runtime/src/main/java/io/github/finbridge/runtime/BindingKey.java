package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import java.util.Objects;
import java.util.UUID;

record BindingKey(UUID tenantId, Capability capability) {
    BindingKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(capability, "capability");
    }
}
