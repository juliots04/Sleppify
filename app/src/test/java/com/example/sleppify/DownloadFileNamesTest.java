package com.example.sleppify;

import org.junit.Test;
import static org.junit.Assert.*;

public class DownloadFileNamesTest {
    @Test public void removesPathSeparatorsAndKeepsRealAudioFormat() {
        String name = DownloadFileNames.forTrack("abc123", "Song/Live: 2026", "Artist\\Band", "webm");
        assertEquals("Artist_Band - Song_Live_ 2026 [abc123].webm", name);
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
    }

    @Test public void distinguishesIdenticalTitlesByTrackId() {
        assertNotEquals(DownloadFileNames.forTrack("a", "Song", "Artist", "m4a"),
                DownloadFileNames.forTrack("b", "Song", "Artist", "m4a"));
    }

    @Test public void boundsLongUnicodeNamesWithoutSplittingSurrogatePairs() {
        String title = new String(new char[99]).replace('\0', 'x') + "\uD83C\uDFB5";
        String name = DownloadFileNames.forTrack("abc", title, "", "mp3");
        assertEquals(99 + " [abc].mp3".length(), name.length());
        assertFalse(Character.isHighSurrogate(name.charAt(98)));
    }

    @Test public void invalidExtensionCannotEscapeDestination() {
        assertEquals("abc [abc].m4a", DownloadFileNames.forTrack("abc", "", null, "../../file"));
    }
}
