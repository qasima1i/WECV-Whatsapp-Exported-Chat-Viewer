package com.wecv.viewer;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Media gallery (v8, tabbed in v9): one screen, two swipe tabs.
//  - Media tab: every photo and video, newest first; tap opens the viewer,
//    long-press jumps back into the chat at that message.
//  - Voice tab: every voice note, newest first, with its own player built
//    the v7 way (the app keeps its own playback state and clock — the
//    player is never asked for its state). Tap a row to jump into the chat
//    at that voice note; the play button plays it here.
public class GalleryActivity extends Activity {
    static class Item {
        String file;
        boolean video;
        String label;
        int idx;
    }

    static class Voice {
        int idx;
        String file, dateLabel, time, name;
        int sender;
        float dur;
        int[] peaks;
    }

    File chatDir;
    final List<Item> items = new ArrayList<>();
    final List<Voice> voices = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024) {
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    float density;

    TextView title, tabMedia, tabVoice;
    View tabMediaLine, tabVoiceLine;
    GridView grid;
    ListView voiceList;
    boolean voiceTab = false;

    // voice player state (own state + own clock, same lesson as chat v7)
    MediaPlayer vp;
    int vPos = -1; // position in voices list
    int vState = 0; // 0 idle, 1 playing, 2 paused
    long vPosMs = 0, vStampMs = 0;
    int vDurMs = 0;
    Runnable vTick;

    int dp(float v) { return (int) (v * density + 0.5f); }

    String fmtDur(float sec) {
        int s = Math.round(sec);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        String cd = getIntent().getStringExtra("chatDir");
        chatDir = cd != null ? new File(cd) : getFilesDir();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B141A);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1F2C34);
        header.setPadding(dp(6), dp(7), dp(12), dp(7));
        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(22);
        back.setTextColor(0xFFE9EDEF);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        title = new TextView(this);
        title.setText("Media");
        title.setTextSize(17);
        title.setTextColor(0xFFE9EDEF);
        header.addView(title);
        root.addView(header);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xFF1F2C34);
        tabMedia = makeTab("MEDIA", true);
        tabVoice = makeTab("VOICE", false);
        LinearLayout tm = new LinearLayout(this);
        tm.setOrientation(LinearLayout.VERTICAL);
        tm.addView(tabMedia);
        tabMediaLine = new View(this);
        tm.addView(tabMediaLine, new LinearLayout.LayoutParams(-1, dp(3)));
        tabs.addView(tm, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout tw = new LinearLayout(this);
        tw.setOrientation(LinearLayout.VERTICAL);
        tw.addView(tabVoice);
        tabVoiceLine = new View(this);
        tw.addView(tabVoiceLine, new LinearLayout.LayoutParams(-1, dp(3)));
        tabs.addView(tw, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs);
        tabMedia.setOnClickListener(v -> showTab(false));
        tabVoice.setOnClickListener(v -> showTab(true));
        styleTabs();

        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(2), dp(2), dp(2), dp(2));
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        voiceList = new ListView(this);
        voiceList.setDivider(new android.graphics.drawable.ColorDrawable(0xFF1F2C34));
        voiceList.setDividerHeight(dp(1));
        voiceList.setVisibility(View.GONE);
        content.addView(voiceList, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // swipe between tabs
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > dp(60) && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                    if (dx < 0 && !voiceTab) showTab(true);
                    else if (dx > 0 && voiceTab) showTab(false);
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener swipe = (v, e) -> { gd.onTouchEvent(e); return false; };
        grid.setOnTouchListener(swipe);
        voiceList.setOnTouchListener(swipe);

        new Thread(() -> {
            try {
                InputStream is = new java.io.FileInputStream(new File(chatDir, "data.json"));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                JSONArray arr = new JSONObject(bos.toString("UTF-8")).getJSONArray("msgs");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String k = o.getString("k");
                    String f = o.optString("f", "");
                    if ((k.equals("photo") || k.equals("video")) && f.length() > 0) {
                        Item it = new Item();
                        it.file = f;
                        it.video = k.equals("video");
                        it.label = o.getString("d") + " \u2022 " + o.getString("t");
                        it.idx = i;
                        items.add(it);
                    } else if (k.equals("audio") && f.length() > 0) {
                        Voice vc = new Voice();
                        vc.idx = i;
                        vc.file = f;
                        vc.dateLabel = o.getString("d");
                        vc.time = o.getString("t");
                        vc.sender = o.getInt("s");
                        vc.name = o.optString("n", "");
                        JSONArray w = o.optJSONArray("w");
                        if (w != null) {
                            vc.dur = (float) w.getDouble(0);
                            String peaks = w.getString(1);
                            vc.peaks = new int[peaks.length()];
                            for (int j = 0; j < peaks.length(); j++) vc.peaks[j] = Character.digit(peaks.charAt(j), 36);
                        }
                        voices.add(vc);
                    }
                }
                Collections.reverse(items); // newest first, like WhatsApp
                Collections.reverse(voices);
            } catch (Exception ignored) { }
            ui.post(() -> {
                updateTitle();
                grid.setAdapter(new MediaAdapter());
                voiceList.setAdapter(new VoiceAdapter());
            });
        }).start();
    }

    TextView makeTab(String s, boolean first) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(8));
        return t;
    }

    void styleTabs() {
        tabMedia.setTextColor(voiceTab ? 0xFF8696A0 : 0xFFE9EDEF);
        tabVoice.setTextColor(voiceTab ? 0xFFE9EDEF : 0xFF8696A0);
        tabMediaLine.setBackgroundColor(voiceTab ? 0x00000000 : 0xFF00A884);
        tabVoiceLine.setBackgroundColor(voiceTab ? 0xFF00A884 : 0x00000000);
    }

    void showTab(boolean voice) {
        if (voice == voiceTab) return;
        voiceTab = voice;
        if (!voice) stopVoice();
        grid.setVisibility(voice ? View.GONE : View.VISIBLE);
        voiceList.setVisibility(voice ? View.VISIBLE : View.GONE);
        styleTabs();
        updateTitle();
    }

    void updateTitle() {
        title.setText(voiceTab ? "Voice \u2022 " + voices.size() : "Media \u2022 " + items.size());
    }

    // ---------------- media tab ----------------
    class MediaAdapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View cv, ViewGroup parent) {
            FrameLayout cell;
            ImageView iv;
            TextView badge;
            if (cv instanceof FrameLayout) {
                cell = (FrameLayout) cv;
                iv = (ImageView) cell.getChildAt(0);
                badge = (TextView) cell.getChildAt(1);
            } else {
                cell = new FrameLayout(GalleryActivity.this);
                iv = new ImageView(GalleryActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                badge = new TextView(GalleryActivity.this);
                badge.setText("\u25B6");
                badge.setTextSize(13);
                badge.setTextColor(0xFFFFFFFF);
                badge.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0x99000000);
                badge.setBackground(g);
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(26), dp(26));
                bl.gravity = Gravity.CENTER;
                cell.addView(badge, bl);
                int side = grid.getColumnWidth();
                cell.setLayoutParams(new GridView.LayoutParams(side > 0 ? side : dp(110), side > 0 ? side : dp(110)));
            }
            final Item it = items.get(p);
            badge.setVisibility(it.video ? View.VISIBLE : View.GONE);
            loadThumb(iv, "thumbs/" + it.file + ".jpg", it.file);
            cell.setOnClickListener(v -> {
                Intent in = new Intent(GalleryActivity.this, it.video ? VideoActivity.class : PhotoActivity.class);
                in.putExtra("file", it.file);
                in.putExtra("msgIndex", it.idx);
                in.putExtra("label", it.label);
                in.putExtra("chatDir", chatDir.getAbsolutePath());
                startActivity(in);
            });
            cell.setOnLongClickListener(v -> {
                startActivity(ChatUtil.jumpMsgIntent(GalleryActivity.this, it.idx));
                return true;
            });
            return cell;
        }
    }

    void loadThumb(ImageView iv, String assetPath, String key) {
        Bitmap bm = cache.get(key);
        if (bm != null) { iv.setImageBitmap(bm); return; }
        iv.setImageBitmap(null);
        iv.setTag(key);
        io.execute(() -> {
            try {
                InputStream is = new java.io.FileInputStream(new File(chatDir, assetPath));
                Bitmap b = BitmapFactory.decodeStream(is);
                is.close();
                if (b != null) {
                    cache.put(key, b);
                    ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(b); });
                }
            } catch (Exception ignored) { }
        });
    }

    // ---------------- voice tab ----------------
    static class VHolder {
        TextView playBtn, durTv, dateTv;
        ChatActivity.WaveformView wave;
        int pos;
    }

    class VoiceAdapter extends BaseAdapter {
        public int getCount() { return voices.size(); }
        public Object getItem(int p) { return voices.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View cv, ViewGroup parent) {
            VHolder h;
            LinearLayout root;
            if (cv instanceof LinearLayout && cv.getTag() instanceof VHolder) {
                root = (LinearLayout) cv;
                h = (VHolder) cv.getTag();
            } else {
                h = new VHolder();
                root = new LinearLayout(GalleryActivity.this);
                root.setOrientation(LinearLayout.HORIZONTAL);
                root.setGravity(Gravity.CENTER_VERTICAL);
                root.setPadding(dp(14), dp(9), dp(12), dp(9));
                h.playBtn = new TextView(GalleryActivity.this);
                h.playBtn.setGravity(Gravity.CENTER);
                h.playBtn.setTextColor(0xFFE9EDEF);
                root.addView(h.playBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout mid = new LinearLayout(GalleryActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1);
                mlp.leftMargin = dp(8);
                mlp.rightMargin = dp(10);
                h.wave = new ChatActivity.WaveformView(GalleryActivity.this);
                h.wave.playedColor = 0xFF53BDEB;
                h.wave.unplayedColor = 0x40FFFFFF;
                mid.addView(h.wave, new LinearLayout.LayoutParams(-1, dp(30)));
                LinearLayout info = new LinearLayout(GalleryActivity.this);
                info.setOrientation(LinearLayout.HORIZONTAL);
                h.durTv = new TextView(GalleryActivity.this);
                h.durTv.setTextSize(12);
                h.durTv.setTextColor(0xFF8696A0);
                info.addView(h.durTv);
                h.dateTv = new TextView(GalleryActivity.this);
                h.dateTv.setTextSize(12);
                h.dateTv.setTextColor(0xFF8696A0);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
                dlp.leftMargin = dp(10);
                info.addView(h.dateTv, dlp);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
                ilp.topMargin = dp(3);
                mid.addView(info, ilp);
                root.addView(mid, mlp);
                TextView chev = new TextView(GalleryActivity.this);
                chev.setText("\u203A");
                chev.setTextSize(22);
                chev.setTextColor(0xFF8696A0);
                root.addView(chev);
                root.setTag(h);
            }
            final Voice vc = voices.get(p);
            h.pos = p;
            boolean cur = p == vPos && vState != 0;
            boolean playingNow = cur && vState == 1;
            h.playBtn.setText(playingNow ? "\u275A\u275A" : "\u25B6");
            h.playBtn.setTextSize(playingNow ? 17 : 24);
            h.playBtn.setOnClickListener(v -> toggleVoice(h.pos));
            h.wave.peaks = vc.peaks;
            h.wave.progress = cur && vDurMs > 0 ? (float) vPosMs / vDurMs : 0f;
            h.wave.invalidate();
            h.durTv.setText(fmtDur(cur ? vPosMs / 1000f : vc.dur));
            h.dateTv.setText((vc.sender == 0 ? "You" : vc.name) + " \u2022 " + vc.dateLabel + " \u2022 " + vc.time);
            final int idx = vc.idx;
            root.setOnClickListener(v -> startActivity(ChatUtil.jumpMsgIntent(GalleryActivity.this, idx)));
            return root;
        }
    }

    void syncVPos() {
        if (vState != 1) return;
        long now = SystemClock.elapsedRealtime();
        long dt = now - vStampMs;
        if (dt > 0) {
            vPosMs += dt;
            if (vDurMs > 0 && vPosMs > vDurMs) vPosMs = vDurMs;
        }
        vStampMs = now;
    }

    void releaseVoice() {
        stopVTicker();
        if (vp != null) {
            try { vp.release(); } catch (Exception ignored) { }
            vp = null;
        }
    }

    void stopVoice() {
        int done = vPos;
        releaseVoice();
        vPos = -1;
        vState = 0;
        vPosMs = 0;
        vDurMs = 0;
        if (done >= 0) updateVoiceRow(done);
    }

    void startVoice(final int pos) {
        Voice vc = voices.get(pos);
        int prev = vPos;
        releaseVoice();
        vPos = pos;
        vState = 1;
        vPosMs = 0;
        vDurMs = (int) (vc.dur * 1000f);
        vStampMs = SystemClock.elapsedRealtime();
        final MediaPlayer mp = new MediaPlayer();
        vp = mp;
        mp.setOnPreparedListener(p -> {
            if (p != vp) return;
            try { p.start(); } catch (Exception ignored) { }
            vStampMs = SystemClock.elapsedRealtime();
            startVTicker();
            updateVoiceRow(vPos);
        });
        mp.setOnCompletionListener(p -> { if (p == vp) stopVoice(); });
        mp.setOnErrorListener((p, what, extra) -> { if (p == vp) stopVoice(); return true; });
        if (prev >= 0 && prev != pos) updateVoiceRow(prev);
        updateVoiceRow(pos);
        try {
            mp.setDataSource(new File(new File(chatDir, "media"), vc.file).getAbsolutePath());
            mp.prepareAsync();
        } catch (Exception e) {
            stopVoice();
        }
    }

    void toggleVoice(int pos) {
        if (pos == vPos && vp != null && vState != 0) {
            if (vState == 1) {
                syncVPos();
                try { vp.pause(); } catch (Exception ignored) { }
                vState = 2;
                stopVTicker();
            } else {
                try { vp.start(); } catch (Exception ignored) { }
                vState = 1;
                vStampMs = SystemClock.elapsedRealtime();
                startVTicker();
            }
            updateVoiceRow(pos);
            return;
        }
        startVoice(pos);
    }

    void startVTicker() {
        stopVTicker();
        vTick = new Runnable() {
            public void run() {
                if (vState == 1 && vPos >= 0) {
                    syncVPos();
                    updateVoiceRow(vPos);
                    ui.postDelayed(this, 100);
                }
            }
        };
        ui.postDelayed(vTick, 100);
    }

    void stopVTicker() {
        if (vTick != null) ui.removeCallbacks(vTick);
        vTick = null;
    }

    void updateVoiceRow(int pos) {
        if (voiceList == null || pos < 0 || pos >= voices.size()) return;
        int first = voiceList.getFirstVisiblePosition();
        int last = voiceList.getLastVisiblePosition();
        if (pos < first || pos > last) return;
        View child = voiceList.getChildAt(pos - first);
        if (child == null || !(child.getTag() instanceof VHolder)) return;
        VHolder h = (VHolder) child.getTag();
        if (h.pos != pos) return;
        Voice vc = voices.get(pos);
        boolean cur = pos == vPos && vState != 0;
        boolean playingNow = cur && vState == 1;
        h.playBtn.setText(playingNow ? "\u275A\u275A" : "\u25B6");
        h.playBtn.setTextSize(playingNow ? 17 : 24);
        h.wave.progress = cur && vDurMs > 0 ? (float) vPosMs / vDurMs : 0f;
        h.wave.invalidate();
        h.durTv.setText(fmtDur(cur ? vPosMs / 1000f : vc.dur));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vp != null && vState == 1) {
            syncVPos();
            try { vp.pause(); } catch (Exception ignored) { }
            vState = 2;
            stopVTicker();
            updateVoiceRow(vPos);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseVoice();
    }
}
