package com.wecv.viewer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

// Shared helpers: jump back into the chat at one exact message or day, and
// save / share a media file into the phone gallery (MediaStore on API 29+,
// public folder + scan below that).
public class ChatUtil {
    public static Intent jumpMsgIntent(Context c, int msgIndex) {
        Intent i = new Intent(c, ChatActivity.class);
        i.putExtra("jumpMsg", msgIndex);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    public static Intent jumpDayIntent(Context c, String iso) {
        Intent i = new Intent(c, ChatActivity.class);
        i.putExtra("jumpDay", iso);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    public static String mimeFor(String file, boolean video) {
        String f = file.toLowerCase(Locale.US);
        if (video) return f.endsWith(".mov") ? "video/quicktime" : "video/mp4";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    static void copy(InputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
        os.close();
        is.close();
    }

    // Copies a media file into the phone gallery. Blocking: call from a
    // background thread. Returns the content Uri (API 29+) or a file Uri.
    public static Uri saveMedia(Activity a, File src, String displayName, boolean video) throws Exception {
        String mime = mimeFor(displayName, video);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/WECV");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = a.getContentResolver().insert(
                    video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            OutputStream os = a.getContentResolver().openOutputStream(uri);
            copy(new FileInputStream(src), os);
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            a.getContentResolver().update(uri, cv, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "WECV");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, displayName);
        if (!out.exists()) copy(new FileInputStream(src), new FileOutputStream(out));
        MediaScannerConnection.scanFile(a, new String[]{out.getAbsolutePath()}, new String[]{mime}, null);
        return Uri.fromFile(out);
    }

    public static void saveWithToast(final Activity a, final File src, final boolean video) {
        new Thread(() -> {
            try {
                Uri u = saveMedia(a, src, src.getName(), video);
                a.runOnUiThread(() -> Toast.makeText(a,
                        u != null ? "Gallery mein save ho gaya" : "Save nahi ho saka",
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Save nahi ho saka", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    public static void shareWithChooser(final Activity a, final File src, final boolean video) {
        new Thread(() -> {
            try {
                final Uri uri = saveMedia(a, src, src.getName(), video);
                a.runOnUiThread(() -> {
                    if (uri == null || (Build.VERSION.SDK_INT < 29 && "file".equals(uri.getScheme()))) {
                        Toast.makeText(a, "Share ke liye pehle Save kar lein", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType(mimeFor(src.getName(), video));
                    s.putExtra(Intent.EXTRA_STREAM, uri);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(s, "Share"));
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Share nahi ho saka", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
