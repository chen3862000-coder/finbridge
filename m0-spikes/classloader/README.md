# M0 External JAR and Class-Loader Spike

This isolated spike is architecture evidence, not a product module. It compiles a shared SPI into the host side, then builds two external provider JARs that each package a different implementation of the same private helper class (`spike.privateapi.Helper`). The provider JARs are not on the host classpath. A controlled per-plugin loader discovers them using `ServiceLoader`.

Run with JDK 21:

```bash
JAVA_HOME="/path/to/jdk-21" PATH="/path/to/jdk-21/bin:$PATH" ./m0-spikes/classloader/run.sh
```

The script creates all class files/JARs in a temporary directory and removes them on exit. It asserts that both providers execute their own helper version, both providers implement the parent-loaded SPI interface, and the private helper classes have distinct defining class loaders. No Maven/Spring dependency or host product implementation is included in this spike.

This verifies the narrow JVM class identity/delegation premise only. It does not prove the production runtime lifecycle, arbitrary plugin security, hot installation API, safe unload, or payment/workflow guarantees.
