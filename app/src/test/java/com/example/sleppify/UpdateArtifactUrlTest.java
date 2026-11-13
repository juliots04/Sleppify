package com.example.sleppify;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UpdateArtifactUrlTest {
    @Test public void resolvesPublishedRelativePathAgainstManifestLocation() {
        assertEquals("https://updates.example/releases/apk/sleppify.apk",
                UpdateArtifactUrl.resolve("https://updates.example/releases/version.json", "apk/sleppify.apk"));
    }

    @Test public void handlesRootPathWithoutDuplicatingSlashes() {
        assertEquals("https://updates.example/apk/sleppify.apk",
                UpdateArtifactUrl.resolve("https://updates.example/releases/version.json", "/apk/sleppify.apk"));
    }

    @Test public void preservesCdnLinksAndEncodedNames() {
        assertEquals("https://cdn.example/sleppify%20new.apk?token=abc",
                UpdateArtifactUrl.resolve("https://updates.example/version.json", "https://cdn.example/sleppify%20new.apk?token=abc"));
        assertEquals("https://updates.example/apk/sleppify%20new.apk",
                UpdateArtifactUrl.resolve("https://updates.example/version.json", "apk/sleppify new.apk"));
    }

    @Test public void rejectsMissingOrNonNetworkArtifacts() {
        for (String artifact : new String[]{"", "file:///tmp/app.apk", "javascript:alert(1)", "apk\\file.apk"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> UpdateArtifactUrl.resolve("https://updates.example/version.json", artifact));
        }
    }
}
