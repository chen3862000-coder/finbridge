package io.github.finbridge.runtime;

import java.net.URL;
import java.net.URLClassLoader;

final class PluginClassLoader extends URLClassLoader {
    private static final String API_PACKAGE = "io.github.finbridge.spi.";

    PluginClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        if (name.startsWith("java.") || name.startsWith(API_PACKAGE)) {
            return super.loadClass(name, resolve);
        }

        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            try {
                loaded = findClass(name);
            } catch (ClassNotFoundException absentFromPlugin) {
                loaded = super.loadClass(name, false);
            }
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }

}
