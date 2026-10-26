package spike.host;

import java.net.URL;
import java.net.URLClassLoader;

public final class ChildFirstPluginClassLoader extends URLClassLoader {
    private static final String SHARED_API = "spike.api.";

    public ChildFirstPluginClassLoader(URL jar, ClassLoader parent) {
        super(new URL[]{jar}, parent);
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        if (name.startsWith("java.") || name.startsWith(SHARED_API)) {
            return super.loadClass(name, resolve);
        }

        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            try {
                loaded = findClass(name);
            } catch (ClassNotFoundException notInPlugin) {
                loaded = super.loadClass(name, false);
            }
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }
}
