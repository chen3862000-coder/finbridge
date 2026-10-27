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

    PluginBinding restore(UUID tenantId, Capability capability, PluginKey pluginKey, long version) {
        BindingKey key = new BindingKey(tenantId, capability);
        PluginBinding restored = new PluginBinding(tenantId, capability, pluginKey, version);
        while (true) {
            Map<BindingKey, PluginBinding> current = snapshot.get();
            if (current.containsKey(key)) {
                throw new IllegalStateException("Binding is already restored");
            }
            Map<BindingKey, PluginBinding> updated = new HashMap<>(current);
            updated.put(key, restored);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                return restored;
            }
        }
    }

    PluginBinding publish(PluginBinding binding) {
        BindingKey key = new BindingKey(binding.tenantId(), binding.capability());
        while (true) {
            Map<BindingKey, PluginBinding> current = snapshot.get();
            PluginBinding present = current.get(key);
            if (present != null && binding.version() <= present.version()) {
                throw new IllegalStateException("Published binding revision must advance monotonically");
            }
            Map<BindingKey, PluginBinding> updated = new HashMap<>(current);
            updated.put(key, binding);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                return binding;
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

    long publishUnbind(UUID tenantId, Capability capability, long nextVersion) {
        BindingKey key = new BindingKey(tenantId, capability);
        while (true) {
            Map<BindingKey, PluginBinding> current = snapshot.get();
            PluginBinding present = current.get(key);
            if (present == null || present.version() + 1 != nextVersion) {
                throw new IllegalStateException("Persisted unbind revision is inconsistent with runtime snapshot");
            }
            Map<BindingKey, PluginBinding> updated = new HashMap<>(current);
            updated.remove(key);
            if (snapshot.compareAndSet(current, Map.copyOf(updated))) {
                return nextVersion;
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
