package com.example.sleppify;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class OfflineDownloadManifestTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void largePlaylistSurvivesReopenWithoutWorkManagerPayloadLimit() throws Exception {
        int count = 5000;
        String[] ids = new String[count], titles = new String[count], artists = new String[count], durations = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = "video-" + i;
            titles[i] = "Canción número " + i + " 🎵";
            artists[i] = "Artista ñ";
            durations[i] = "4:32";
        }
        File directory = folder.newFolder();
        String token = new OfflineDownloadManifest(ids, titles, artists, durations).write(directory);
        assertTrue(new File(directory, token).length() > 10 * 1024);
        assertTrue(token.length() < 100);
        OfflineDownloadManifest restored = OfflineDownloadManifest.read(directory, token);
        assertArrayEquals(ids, restored.ids);
        assertArrayEquals(titles, restored.titles);
        assertArrayEquals(artists, restored.artists);
        assertArrayEquals(durations, restored.durations);
        OfflineDownloadManifest.delete(directory, token);
        assertFalse(new File(directory, token).exists());
    }

    @Test public void veryLongUnicodeTitleAndNullMetadataRoundTrip() throws Exception {
        char[] chars = new char[80_000];
        Arrays.fill(chars, 'ñ');
        String title = new String(chars);
        File directory = folder.newFolder();
        String token = new OfflineDownloadManifest(new String[]{"id"}, new String[]{title},
                new String[]{null}, new String[]{null}).write(directory);
        OfflineDownloadManifest restored = OfflineDownloadManifest.read(directory, token);
        assertEquals(title, restored.titles[0]);
        assertEquals("", restored.artists[0]);
    }

    @Test(expected = IOException.class) public void rejectsTruncatedPayload() throws Exception {
        File directory = folder.newFolder();
        String token = new OfflineDownloadManifest(new String[]{"id"}, new String[]{"title"},
                new String[]{"artist"}, new String[]{"3:00"}).write(directory);
        try (FileOutputStream out = new FileOutputStream(new File(directory, token))) { out.write(1); }
        OfflineDownloadManifest.read(directory, token);
    }

    @Test(expected = IOException.class) public void rejectsPathsOutsideJobDirectory() throws Exception {
        OfflineDownloadManifest.read(folder.newFolder(), "../playlist.tracks");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsMisalignedTrackMetadata() {
        new OfflineDownloadManifest(new String[]{"id"}, new String[0], new String[0], new String[0]);
    }

    @Test public void requestOwnsItsSnapshot() {
        String[] ids = {"first"};
        OfflineDownloadManifest manifest = new OfflineDownloadManifest(ids, ids, ids, ids);
        ids[0] = "changed";
        assertEquals("first", manifest.ids[0]);
    }
}
