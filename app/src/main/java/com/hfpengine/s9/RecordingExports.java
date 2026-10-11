package com.hfpengine.s9;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Publish a complete diagnostic set through scoped storage. Keep sources on failure. */
final class RecordingExports {
    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/S9 HFP Tests/";
    static final String[] SUFFIXES = {"_mic_raw.wav", "_mic_processed.wav",
            "_farend_reference.wav", "_metadata.json"};

    static List<Uri> publish(Context context, String prefix) throws IOException {
        if (Build.VERSION.SDK_INT < 29)
            throw new IOException("Public Downloads export requires Android 10 or newer");
        // Validate the entire set before publishing anything.
        for (String suffix : SUFFIXES) {
            File file = new File(prefix + suffix);
            if (!file.isFile() || file.length() == 0)
                throw new IOException("Missing or empty export: " + file.getName());
        }
        ContentResolver resolver = context.getContentResolver();
        List<Uri> created = new ArrayList<>();
        try {
            for (String suffix : SUFFIXES) {
                File file = new File(prefix + suffix);
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        suffix.endsWith(".wav") ? "audio/wav" : "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Cannot create Downloads file");
                created.add(uri);
                try (FileInputStream in = new FileInputStream(file);
                     OutputStream out = resolver.openOutputStream(uri, "w")) {
                    if (out == null) throw new IOException("Cannot open Downloads file");
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
            for (Uri uri : created) {
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, complete, null, null) != 1)
                    throw new IOException("Cannot finish Downloads export");
            }
            return created;
        } catch (Exception e) {
            for (Uri uri : created) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) { }
            }
            throw new IOException("Downloads export failed; private originals retained", e);
        }
    }

    static void removeSources(String prefix) {
        for (String suffix : SUFFIXES) new File(prefix + suffix).delete();
    }
}
