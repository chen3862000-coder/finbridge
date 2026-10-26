package io.github.finbridge.runtime;

import io.github.finbridge.spi.PluginKey;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

final class PluginRegistry {
    private final Map<PluginKey, PluginHandle> handles = new ConcurrentHashMap<>();

    PluginHandle putIfAbsent(PluginHandle handle) {
        return handles.putIfAbsent(handle.key(), handle);
    }

    List<PluginHandle> plugins() {
        NavigableSet<PluginHandle> ordered = new TreeSet<>(
                java.util.Comparator.comparing(handle -> handle.key().toString()));
        ordered.addAll(handles.values());
        return List.copyOf(ordered);
    }

    PluginHandle plugin(PluginKey key) {
        PluginHandle handle = handles.get(key);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown plugin: " + key);
        }
        return handle;
    }

    boolean remove(PluginKey key, PluginHandle handle) {
        return handles.remove(key, handle);
    }
}
