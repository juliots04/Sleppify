package com.example.sleppify;

import java.net.URI;

final class UpdateArtifactUrl {
    private UpdateArtifactUrl() { }

    static String resolve(String manifestUrl, String artifact) {
        if (artifact == null || artifact.trim().isEmpty() || artifact.contains("\\")) {
            throw new IllegalArgumentException("La actualización no tiene un enlace válido.");
        }
        URI resolved = URI.create(manifestUrl).resolve(artifact.trim().replace(" ", "%20")).normalize();
        String scheme = resolved.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || resolved.getHost() == null || resolved.getUserInfo() != null) {
            throw new IllegalArgumentException("El enlace de actualización no es compatible.");
        }
        return resolved.toASCIIString();
    }
}
