package com.example.sleppify;

/** Portable audio names for MediaStore and document providers, retaining their real format. */
public final class DownloadFileNames {
    private DownloadFileNames() { }
    public static String forTrack(String id, String title, String artist, String extension) {
        String name = ((artist == null || artist.trim().isEmpty() ? "" : artist.trim() + " - ")
                + (title == null || title.trim().isEmpty() ? id : title.trim()))
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        int end = Math.min(name.length(), 100);
        if (end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        String safeId = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        String ext = extension != null && extension.matches("[a-zA-Z0-9]{1,8}") ? extension : "m4a";
        return name.substring(0, end) + " [" + safeId + "]." + ext;
    }
}
