package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class PluginBindingRegistry {
    private final AtomicReference<Map<BindingKey, PluginBinding>> snapshot =
            new AtomicReference<>(Map.of());

    PluginBinding binding(UUID tenantId, Capability capability) {
        PluginBinding binding = snapshot.get().get(new BindingKey(tenantId, capability));
        if (binding == null) {
            throw new IllegalArgumentException("No provider binding for tenant capability");
        }
        return binding;
    }

    PluginBinding bind(UUID tenantId, Capability capability, PluginHandle target, PluginKey pluginKey,
                       long expectedVersion) {
        BindingKey key = new BindingKey(tenantId, capability);
        while (true) {
            Map<BindingKey, PluginBinding> current = snapshot.get();
            PluginBinding previous = current.get(key);
            long version = previous == null ? 0 : previous.version();
            ensureVersion(expectedVersion, version);
            PluginBinding replacement = new PluginBinding(tenantId, capability, pluginKey,
                    version + 1);
            Map<BindingKey, PluginBinding> updated = new HashMap<>(current);
            updated.put(key, replacement);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                return replacement;
            }
        }
    }

    long unbind(UUID tenantId, Capability capability, long expectedVersion) {
        BindingKey key = new BindingKey(tenantId, capability);
        while (true) {
            Map<BindingKey, PluginBinding> current = snapshot.get();
            PluginBinding previous = current.get(key);
            long version = previous == null ? 0 : previous.version();
            ensureVersion(expectedVersion, version);
            if (previous == null) {
                return 0;
            }
            Map<BindingKey, PluginBinding> updated = new HashMap<>(current);
            updated.remove(key);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                return version + 1;
            }
        }
    }

    boolean isBound(PluginKey pluginKey) {
        return snapshot.get().values().stream()
                .anyMatch(binding -> binding.pluginKey().equals(pluginKey));
    }

    private static void ensureVersion(long expected, long actual) {
        if (actual != expected) {
            throw new IllegalStateException("Stale binding version; expected " + expected
                    + " but was " + actual);
        }
    }
}
