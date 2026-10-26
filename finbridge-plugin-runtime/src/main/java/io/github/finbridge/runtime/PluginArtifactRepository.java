package io.github.finbridge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

final class PluginArtifactRepository {
    private static final long MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L;
    private static final String SPI_PATH = "io/github/finbridge/spi/";
    private final Path artifactRoot;

    PluginArtifactRepository(Path artifactRoot) {
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
    }

    Path resolve(String artifactName) {
        if (artifactName.isBlank() || artifactName.contains("/") || artifactName.contains("\\")) {
            throw new PluginLoadException("Artifact name must be a simple local filename");
        }
        try {
            Path root = artifactRoot.toRealPath();
            Path artifact = root.resolve(artifactName).normalize().toRealPath();
            boolean invalid = !artifact.startsWith(root) || !Files.isRegularFile(artifact)
                    || !Files.isReadable(artifact) || Files.size(artifact) > MAX_ARTIFACT_BYTES;
            if (invalid) {
                throw new PluginLoadException("Artifact path, type, permissions, or size is invalid");
            }
            return artifact;
        } catch (IOException failure) {
            throw new PluginLoadException("Could not resolve plugin artifact", failure);
        }
    }

    void validate(Path artifact) {
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(SPI_PATH) && name.endsWith(".class")) {
                    throw new PluginLoadException("Plugin must not bundle shared SPI classes");
                }
            }
        } catch (IOException failure) {
            throw new PluginLoadException("Plugin is not a valid JAR", failure);
        }
    }

    String checksum(Path artifact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new PluginLoadException("Could not compute plugin checksum", failure);
        }
    }
}
