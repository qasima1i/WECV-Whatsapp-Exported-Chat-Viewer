package com.wecv.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Turns a parsed export into the app's data.json (same schema the chat
// screen reads): emoji spans, jumbo flags, real durations and waveform
// peaks for voice notes, and thumbnails for photos/videos. Also writes the
// library meta.json. Runs at import time; enrich() fills in the same data
// later for media files added after the first import.
public class Prep {
    public interface Progress { void onProgress(String what, int done, int total); }

    static Set<String> emojiIds(Context c) {
        Set<String> ids = new HashSet<>();
        try {
            String[] names = c.getAssets().list("emoji");
            if (names != null) for (String n : names) if (n.endsWith(".png")) ids.add(n.substring(0, n.length() - 4));
        } catch (Exception ignored) { }
        return ids;
    }

    static JSONArray spansJson(EmojiScan s) {
        if (s.ids.length == 0) return null;
        JSONArray e = new JSONArray();
        for (int i = 0; i < s.ids.length; i++) {
            JSONArray one = new JSONArray();
            one.put(s.starts[i]);
            one.put(s.ends[i]);
            one.put(s.ids[i]);
            e.put(one);
        }
        return e;
    }

    // 40 waveform bars from the audio file's bytes, base36 like the chat data
    static String peaksFor(File f) {
        try {
            byte[] data = readAll(f, 8 * 1024 * 1024);
            if (data.length == 0) return null;
            int bars = 40;
            int chunk = Math.max(1, data.length / bars);
            StringBuilder sb = new StringBuilder();
            int max = 1;
            int[] vals = new int[bars];
            for (int i = 0; i < bars; i++) {
                long sum = 0;
                int from = i * chunk, to = Math.min(data.length, from + chunk);
                for (int j = from; j < to; j++) sum += Math.abs(data[j]);
                vals[i] = to > from ? (int) (sum / (to - from)) : 0;
                if (vals[i] > max) max = vals[i];
            }
            for (int i = 0; i < bars; i++) {
                int v = (int) Math.round(vals[i] * 35.0 / max);
                sb.append(Character.forDigit(v, 36));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static float durationFor(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d) / 1000f;
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return 0f;
    }

    static boolean makeThumb(File mediaDir, File thumbsDir, String file, String kind) {
        File out = new File(thumbsDir, file + ".jpg");
        if (out.exists()) return true;
        try {
            Bitmap bm = null;
            if ("video".equals(kind)) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(new File(mediaDir, file).getAbsolutePath());
                    bm = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
            } else {
                File src = new File(mediaDir, file);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(src.getAbsolutePath(), o);
                int maxDim = Math.max(o.outWidth, o.outHeight);
                int sample = 1;
                while (maxDim / sample > 480) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                bm = BitmapFactory.decodeFile(src.getAbsolutePath(), o2);
            }
            if (bm == null) return false;
            float sc = Math.min(1f, 480f / Math.max(bm.getWidth(), bm.getHeight()));
            if (sc < 1f) bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * sc), Math.round(bm.getHeight() * sc), true);
            FileOutputStream fos = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] readAll(File f, int cap) throws Exception {
        InputStream is = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > cap) break;
        }
        is.close();
        return bos.toByteArray();
    }

    static String readText(File f) throws Exception {
        return new String(readAll(f, 64 * 1024 * 1024), "UTF-8");
    }

    static void writeText(File f, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }

    public static void build(Context ctx, File chatDir, ChatParser.Result parsed, String me,
                             String titleHint, Progress cb) throws Exception {
        File mediaDir = new File(chatDir, "media");
        File thumbsDir = new File(chatDir, "thumbs");
        thumbsDir.mkdirs();
        Set<String> emoji = emojiIds(ctx);

        List<String> senderOrder = new ArrayList<>(parsed.senders.keySet());
        boolean group = senderOrder.size() > 2 || me == null;
        Map<String, Integer> sIndex = new HashMap<>();
        int next = 1;
        for (String s : senderOrder) {
            if (s.equals(me)) sIndex.put(s, 0);
            else sIndex.put(s, next++);
        }
        String other = null;
        for (String s : senderOrder) if (!s.equals(me)) { other = s; break; }
        String title = titleHint != null && titleHint.length() > 0 ? titleHint
                : group ? "Group chat" : (other != null ? other : "Chat");

        // count media work for progress
        int mediaTotal = 0;
        for (ChatParser.PMsg m : parsed.msgs) if (m.file != null) mediaTotal++;
        int mediaDone = 0;

        JSONArray arr = new JSONArray();
        Set<String> daySet = new HashSet<>();
        int vn = 0, withSender = 0;
        String firstIso = null, lastIso = null, firstLabel = null, lastLabel = null;
        for (ChatParser.PMsg m : parsed.msgs) {
            JSONObject o = new JSONObject();
            o.put("d", m.dateLabel);
            o.put("iso", m.iso);
            o.put("t", m.time);
            o.put("s", m.sender == null ? -1 : sIndex.get(m.sender));
            o.put("k", m.kind);
            if (m.sender != null) { o.put("n", m.sender); withSender++; }
            if (m.file != null) o.put("f", m.file);
            o.put("x", m.text == null ? "" : m.text);
            if (m.omitted) o.put("o", 1);
            if (m.waDeleted) o.put("del", 1);
            if (m.text != null && m.text.length() > 0 && !"audio".equals(m.kind)) {
                EmojiScan sc = EmojiScan.scan(m.text, emoji);
                JSONArray e = spansJson(sc);
                if (e != null) o.put("e", e);
                if (EmojiScan.isJumbo(m.text, sc)) o.put("j", 1);
            }
            if (m.file != null) {
                File mf = new File(mediaDir, m.file);
                if (mf.exists()) {
                    if ("photo".equals(m.kind) || "video".equals(m.kind)) {
                        makeThumb(mediaDir, thumbsDir, m.file, m.kind);
                    } else if ("audio".equals(m.kind)) {
                        vn++;
                        float dur = durationFor(mf);
                        String peaks = peaksFor(mf);
                        if (peaks != null) {
                            JSONArray w = new JSONArray();
                            w.put(dur);
                            w.put(peaks);
                            o.put("w", w);
                        }
                    }
                }
                mediaDone++;
                if (cb != null && mediaDone % 20 == 0) cb.onProgress("Media", mediaDone, mediaTotal);
            }
            daySet.add(m.iso);
            if (firstIso == null) { firstIso = m.iso; firstLabel = m.dateLabel; }
            lastIso = m.iso;
            lastLabel = m.dateLabel;
            arr.put(o);
        }
        if (cb != null) cb.onProgress("Media", mediaTotal, mediaTotal);

        JSONObject meta = new JSONObject();
        meta.put("friendName", title);
        JSONArray fe = spansJson(EmojiScan.scan(title, emoji));
        if (fe != null) meta.put("friendEmo", fe);
        meta.put("firstIso", firstIso == null ? "" : firstIso);
        meta.put("lastIso", lastIso == null ? "" : lastIso);
        meta.put("firstLabel", firstLabel == null ? "" : firstLabel);
        meta.put("lastLabel", lastLabel == null ? "" : lastLabel);
        meta.put("days", daySet.size());
        meta.put("withSender", withSender);
        meta.put("vn", vn);
        JSONObject root = new JSONObject();
        root.put("meta", meta);
        root.put("msgs", arr);
        writeText(new File(chatDir, "data.json"), root.toString());

        JSONObject lib = new JSONObject();
        lib.put("title", title);
        lib.put("me", me == null ? "" : me);
        lib.put("group", group);
        lib.put("firstLabel", firstLabel == null ? "" : firstLabel);
        lib.put("lastLabel", lastLabel == null ? "" : lastLabel);
        lib.put("count", withSender);
        writeText(new File(chatDir, "meta.json"), lib.toString());
    }

    // After extra media files were copied into media/: generate any missing
    // thumbnails / waveforms / durations and patch data.json.
    public static int enrich(File chatDir) {
        try {
            File mediaDir = new File(chatDir, "media");
            File thumbsDir = new File(chatDir, "thumbs");
            thumbsDir.mkdirs();
            JSONObject root = new JSONObject(readText(new File(chatDir, "data.json")));
            JSONArray arr = root.getJSONArray("msgs");
            int changed = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String f = o.optString("f", "");
                if (f.length() == 0) continue;
                File mf = new File(mediaDir, f);
                if (!mf.exists()) continue;
                String k = o.getString("k");
                if ((k.equals("photo") || k.equals("video")) && !new File(thumbsDir, f + ".jpg").exists()) {
                    if (makeThumb(mediaDir, thumbsDir, f, k)) changed++;
                } else if (k.equals("audio") && !o.has("w")) {
                    String peaks = peaksFor(mf);
                    if (peaks != null) {
                        JSONArray w = new JSONArray();
                        w.put(durationFor(mf));
                        w.put(peaks);
                        o.put("w", w);
                        changed++;
                    }
                }
            }
            if (changed > 0) writeText(new File(chatDir, "data.json"), root.toString());
            return changed;
        } catch (Exception e) {
            return 0;
        }
    }
}
