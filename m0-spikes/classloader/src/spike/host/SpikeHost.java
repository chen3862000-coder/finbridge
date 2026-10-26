package spike.host;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ServiceLoader;
import spike.api.Provider;

public final class SpikeHost {
    private SpikeHost() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected two external plugin JAR paths");
        }

        ClassLoader sharedApiLoader = Provider.class.getClassLoader();
        Class<?>[] helperTypes = new Class<?>[2];
        String[] results = new String[2];

        for (int i = 0; i < args.length; i++) {
            URL jar = Path.of(args[i]).toUri().toURL();
            try (ChildFirstPluginClassLoader pluginLoader =
                         new ChildFirstPluginClassLoader(jar, ClassLoader.getSystemClassLoader())) {
                Provider provider = ServiceLoader.load(Provider.class, pluginLoader)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No provider discovered in " + jar));
                if (provider.getClass().getClassLoader() != pluginLoader) {
                    throw new AssertionError("Provider was not defined by its plugin loader");
                }
                if (!Provider.class.isAssignableFrom(provider.getClass())
                        || Provider.class.getClassLoader() != sharedApiLoader) {
                    throw new AssertionError("SPI identity was not shared with the host");
                }

                results[i] = provider.call();
                helperTypes[i] = provider.helperClass();
                if (helperTypes[i].getClassLoader() != pluginLoader) {
                    throw new AssertionError("Private helper escaped its plugin loader");
                }
                Method marker = helperTypes[i].getMethod("marker");
                System.out.println(provider.getClass().getName()
                        + " -> " + results[i]
                        + " | SPI loader=" + Provider.class.getClassLoader()
                        + " | helper loader=" + helperTypes[i].getClassLoader()
                        + " | marker=" + marker.invoke(null));
            }
        }

        if (!"helper-v1".equals(results[0]) || !"helper-v2".equals(results[1])) {
            throw new AssertionError("Providers did not resolve their expected private versions");
        }
        if (helperTypes[0] == helperTypes[1]
                || helperTypes[0].getClassLoader() == helperTypes[1].getClassLoader()) {
            throw new AssertionError("Conflicting helpers unexpectedly share class identity");
        }
        System.out.println("PASS: external SPI discovery and private dependency isolation");
    }
}
