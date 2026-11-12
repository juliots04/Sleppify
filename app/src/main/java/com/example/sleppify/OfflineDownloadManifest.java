package com.example.sleppify;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable track payload; WorkManager Data is limited to 10 KiB. No Android dependencies. */
final class OfflineDownloadManifest {
    private static final int VERSION = 1;
    final String[] ids, titles, artists, durations;

    OfflineDownloadManifest(String[] ids, String[] titles, String[] artists, String[] durations) {
        if (ids.length != titles.length || ids.length != artists.length || ids.length != durations.length) {
            throw new IllegalArgumentException("Track columns must have equal lengths");
        }
        this.ids = ids.clone();
        this.titles = titles.clone();
        this.artists = artists.clone();
        this.durations = durations.clone();
    }

    String write(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create download directory");
        String token = UUID.randomUUID() + ".tracks";
        File file = resolve(directory, token);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(VERSION);
            out.writeInt(ids.length);
            for (int i = 0; i < ids.length; i++) {
                writeString(out, ids[i]);
                writeString(out, titles[i]);
                writeString(out, artists[i]);
                writeString(out, durations[i]);
            }
        } catch (IOException e) {
            file.delete();
            throw e;
        }
        return token;
    }

    static OfflineDownloadManifest read(File directory, String token) throws IOException {
        File file = resolve(directory, token);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != VERSION) throw new IOException("Unsupported download manifest");
            int count = in.readInt();
            if (count < 0 || count > 1_000_000 || count > (file.length() - 8) / 16) throw new IOException("Invalid track count");
            String[] ids = new String[count], titles = new String[count], artists = new String[count], durations = new String[count];
            for (int i = 0; i < count; i++) {
                ids[i] = readString(in);
                titles[i] = readString(in);
                artists[i] = readString(in);
                durations[i] = readString(in);
            }
            return new OfflineDownloadManifest(ids, titles, artists, durations);
        }
    }

    static void delete(File directory, String token) {
        try { resolve(directory, token).delete(); } catch (IOException ignored) { }
    }

    private static File resolve(File directory, String token) throws IOException {
        if (token == null || !token.matches("[0-9a-fA-F-]{36}\\.tracks")) throw new IOException("Invalid manifest name");
        return new File(directory, token);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("Track field too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 4 * 1024 * 1024) throw new IOException("Invalid track field size");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
