package com.local.spotdlnative;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class MediaPublisher {
    private static final String LIBRARY_DIR = "SpotifyDownloads";

    private MediaPublisher() {
    }

    static int publish(Context context, JSONArray files, File outputRoot) throws Exception {
        int count = 0;
        for (int i = 0; i < files.length(); i++) {
            File source = new File(files.getString(i));
            if (!source.isFile()) {
                continue;
            }
            publishOne(context, source, outputRoot);
            count++;
        }
        return count;
    }

    private static void publishOne(Context context, File source, File outputRoot) throws IOException {
        String relative = relativePath(source, outputRoot);
        String parent = parentPath(relative);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source, parent);
        } else {
            publishLegacy(context, source, parent);
        }
    }

    private static void publishWithMediaStore(Context context, File source, String parent) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType(source));
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativeMediaPath(parent));
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Unable to create MediaStore row for " + source.getName());
        }

        try (OutputStream out = resolver.openOutputStream(uri);
             FileInputStream in = new FileInputStream(source)) {
            if (out == null) {
                throw new IOException("Unable to open MediaStore output for " + source.getName());
            }
            copy(in, out);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
    }

    private static void publishLegacy(Context context, File source, String parent) throws IOException {
        File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), LIBRARY_DIR);
        if (!parent.isEmpty()) {
            targetDir = new File(targetDir, parent);
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("Unable to create " + targetDir);
        }
        File target = new File(targetDir, source.getName());
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, null, null);
    }

    private static String relativePath(File source, File outputRoot) throws IOException {
        String root = outputRoot.getCanonicalPath();
        String path = source.getCanonicalPath();
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1);
        }
        return source.getName();
    }

    private static String parentPath(String relative) {
        int index = relative.lastIndexOf(File.separatorChar);
        if (index <= 0) {
            return "";
        }
        return relative.substring(0, index);
    }

    private static String relativeMediaPath(String parent) {
        List<String> parts = new ArrayList<>();
        parts.add(Environment.DIRECTORY_MUSIC);
        parts.add(LIBRARY_DIR);
        if (!parent.isEmpty()) {
            parts.add(parent.replace(File.separatorChar, '/'));
        }
        return String.join("/", parts) + "/";
    }

    private static String mimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (type != null) {
                return type;
            }
        }
        if (name.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (name.endsWith(".flac")) {
            return "audio/flac";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        return "audio/*";
    }

    private static void copy(FileInputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
