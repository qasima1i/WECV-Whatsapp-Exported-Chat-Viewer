package com.wecv.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends Activity {
    // message kinds
    static final int K_SYS = 0, K_TEXT = 1, K_PHOTO = 2, K_STICKER = 3, K_VIDEO = 4, K_AUDIO = 5, K_DOC = 6, K_DOCMISS = 7, K_CALL = 8;
    static final int ROW_SEP = 0, ROW_MSG = 1;

    static class Msg {
        String dateLabel, iso, time, file, text, name;
        int sender; // -1 system, 0 me, 1 jaan
        int kind;
        int[] spanStart, spanEnd;
        String[] spanId;
        float dur;
        int[] peaks;
        boolean omitted, waDeleted, jumbo;
    }

    // palette
    int cBg, cHeader, cMine, cTheirs, cText, cMineText, cDim, cChip, cChipText, cInput, cName;
    boolean light;

    SharedPreferences prefs;
    String theme, bubbleKey;
    Set<String> deleted = new HashSet<>();
    Set<String> starred = new HashSet<>();

    List<Msg> msgs = new ArrayList<>();
    List<int[]> rows = new ArrayList<>(); // [rowType, msgIndex]
    List<String> dayIso = new ArrayList<>();
    List<String> dayLabel = new ArrayList<>();
    List<Integer> dayFirstRow = new ArrayList<>();
    int[] msgRow;
    String[] lowerTexts;
    String chatId;
    File chatDir;
    String chatTitle = "Chat";
    String meName = "";
    boolean groupChat = false;
    TextView subtitleView;
    final HashMap<String, Bitmap> avatarCache = new HashMap<>();
    JSONObject meta;

    ListView listView;
    ChatAdapter adapter;
    LinearLayout searchBar;
    EditText searchBox;
    TextView searchInfo, pinnedDate;
    ImageView dpView, bgImage;
    View scrim;
    TextView fab;
    FrameLayout rootFrame;
    FrameLayout loadingOverlay;
    TextView headerNameView;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService ioExec = Executors.newFixedThreadPool(3);
    final HashMap<String, Drawable> emojiInline = new HashMap<>();
    final HashMap<String, Drawable> emojiJumbo = new HashMap<>();
    final LruCache<String, Bitmap> bmpCache = new LruCache<String, Bitmap>(24 * 1024) {
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    final LruCache<Integer, Spannable> spanCache = new LruCache<>(400);
    Spannable friendNameSpan;

    // audio state — v7: the UI is driven ONLY by these fields, never by querying
    // MediaPlayer (on William's device the player plays the sound fully but its
    // reported state/position never reaches the UI: button stayed ▶, seconds and
    // wave frozen, even after a rebind). Position comes from our own clock.
    MediaPlayer player;
    int playingMsg = -1;
    int audioState = 0; // 0 idle, 1 playing, 2 paused
    long audioPosMs = 0;
    long audioStampMs = 0;
    int audioDurMs = 0;
    Runnable progressTick;

    int highlightMsg = -1, highlightRow = -1;

    float density;
    int dp(float v) { return (int) (v * density + 0.5f); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        chatId = getIntent().getStringExtra("chatId");
        if (chatId == null) { finish(); return; }
        chatDir = new File(getFilesDir(), "chats/" + chatId);
        loadLibMeta();
        prefs = getSharedPreferences("wecv_" + chatId, MODE_PRIVATE);
        theme = prefs.getString("theme", "dark");
        bubbleKey = prefs.getString("bubble", "default");
        deleted = new HashSet<>(prefs.getStringSet("deleted", new HashSet<String>()));
        starred = new HashSet<>(prefs.getStringSet("starred", new HashSet<String>()));
        applyPalette();
        buildUi();
        loadData();
        handleJumpIntent(getIntent());
    }

    void applyPalette() {
        light = "light".equals(theme);
        if (light) {
            cBg = 0xFFEFEAE2; cHeader = 0xFFF0F2F5; cMine = 0xFFD9FDD3; cTheirs = 0xFFFFFFFF;
            cText = 0xFF111B21; cMineText = 0xFF111B21; cDim = 0xFF667781; cChip = 0xFFFFFFFF; cChipText = 0xFF3B4A54;
            cInput = 0xFFF0F2F5; cName = 0xFFD35400;
        } else {
            cBg = 0xFF0B141A; cHeader = 0xFF1F2C34; cMine = 0xFF005C4B; cTheirs = 0xFF1F2C34;
            cText = 0xFFE9EDEF; cMineText = 0xFFE9EDEF; cDim = 0xFF8696A0; cChip = 0xFF182229; cChipText = 0xFFCFD8DC;
            cInput = 0xFF2A3942; cName = 0xFFFF9F43;
        }
        String b = bubbleKey;
        if (!"default".equals(b)) {
            int mine = 0, theirsD = 0, theirsL = 0;
            if ("red".equals(b)) { mine = 0xFFB3271E; theirsD = 0xFF3D272C; theirsL = 0xFFFBE9E7; }
            if ("blue".equals(b)) { mine = 0xFF1565C0; theirsD = 0xFF243746; theirsL = 0xFFE3F0FB; }
            if ("purple".equals(b)) { mine = 0xFF6A1B9A; theirsD = 0xFF2E2438; theirsL = 0xFFF1E5F5; }
            if ("teal".equals(b)) { mine = 0xFF00796B; theirsD = 0xFF1E3230; theirsL = 0xFFE0F2F1; }
            if ("orange".equals(b)) { mine = 0xFFE65100; theirsD = 0xFF38302A; theirsL = 0xFFFDEBD0; }
            if ("pink".equals(b)) { mine = 0xFFAD1457; theirsD = 0xFF382630; theirsL = 0xFFFCE4EC; }
            cMine = mine; cMineText = 0xFFFFFFFF; cTheirs = light ? theirsL : theirsD;
        }
    }

    // ---------------- UI construction ----------------
    TextView tv(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable bubbleBg(int color, boolean mine) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(10), tail = dp(1.5f);
        if (mine) g.setCornerRadii(new float[]{r, r, tail, tail, r, r, r, r});
        else g.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
        return g;
    }

    void buildUi() {
        rootFrame = new FrameLayout(this);
        bgImage = new ImageView(this);
        bgImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgImage.setVisibility(View.GONE);
        rootFrame.addView(bgImage, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this);
        scrim.setBackgroundColor(0xC4040C09);
        scrim.setVisibility(View.GONE);
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor("photo".equals(theme) ? Color.TRANSPARENT : cBg);
        rootFrame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(cHeader);
        header.setPadding(dp(10), dp(7), dp(6), dp(7));
        dpView = new ImageView(this);
        dpView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        dpView.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        dpView.setClipToOutline(true);
        header.addView(dpView, new LinearLayout.LayoutParams(dp(40), dp(40)));
        loadAvatar();
        dpView.setOnClickListener(v -> showStats());

        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setPadding(dp(10), 0, dp(4), 0);
        headerNameView = new TextView(this);
        headerNameView.setTextSize(16.5f);
        headerNameView.setTypeface(Typeface.DEFAULT_BOLD);
        headerNameView.setTextColor(cText);
        headerNameView.setSingleLine(true);
        headerNameView.setText(chatTitle);
        who.addView(headerNameView);
        subtitleView = tv("WhatsApp exported chat", 12.5f, cDim);
        who.addView(subtitleView);
        header.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        who.setOnClickListener(v -> showStats());

        header.addView(headerIcon("1f50d", v -> toggleSearch()));
        header.addView(headerIcon("1f4c5", v -> showDatePicker()));
        TextView menuBtn = tv("\u22EE", 22, cText);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setOnClickListener(v -> showMenu(v));
        header.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(header);

        // search bar
        searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(cHeader);
        searchBar.setPadding(dp(12), dp(8), dp(8), dp(8));
        searchBar.setVisibility(View.GONE);
        searchBox = new EditText(this);
        searchBox.setHint("Search messages");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(16);
        searchBox.setTextColor(cText);
        searchBox.setHintTextColor(cDim);
        searchBox.setBackground(roundRect(cInput, 10));
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
        searchInfo = tv("", 12.5f, cDim);
        searchInfo.setPadding(dp(10), 0, dp(6), 0);
        searchBar.addView(searchInfo);
        TextView closeS = tv("\u2715", 17, cText);
        closeS.setGravity(Gravity.CENTER);
        closeS.setOnClickListener(v -> { searchBar.setVisibility(View.GONE); clearHighlight(); hideKeyboard(); });
        searchBar.addView(closeS, new LinearLayout.LayoutParams(dp(38), dp(38)));
        root.addView(searchBar);
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { runSearch(); return true; }
            return false;
        });

        // list area with overlays
        FrameLayout listWrap = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setBackgroundColor("photo".equals(theme) ? Color.TRANSPARENT : cBg);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setScrollingCacheEnabled(false);
        listWrap.addView(listView, new FrameLayout.LayoutParams(-1, -1));

        pinnedDate = tv("", 12, cChipText);
        pinnedDate.setBackground(roundRect(cChip, 8));
        pinnedDate.setPadding(dp(12), dp(5), dp(12), dp(5));
        pinnedDate.setVisibility(View.GONE);
        FrameLayout.LayoutParams pdp = new FrameLayout.LayoutParams(-2, -2);
        pdp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pdp.topMargin = dp(8);
        listWrap.addView(pinnedDate, pdp);

        fab = tv("\u2B07", 20, cText);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(cHeader);
        fabBg.setStroke(dp(1), cDim);
        fab.setBackground(fabBg);
        fab.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(44), dp(44));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = dp(14);
        flp.bottomMargin = dp(16);
        listWrap.addView(fab, flp);
        fab.setOnClickListener(v -> { if (adapter != null && adapter.getCount() > 0) listView.setSelection(adapter.getCount() - 1); });

        root.addView(listWrap, new LinearLayout.LayoutParams(-1, 0, 1));

        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(cBg);
        ProgressBar pb = new ProgressBar(this);
        FrameLayout.LayoutParams pbl = new FrameLayout.LayoutParams(-2, -2);
        pbl.gravity = Gravity.CENTER;
        loadingOverlay.addView(pb, pbl);
        rootFrame.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));

        applyWallpaper();
        setContentView(rootFrame);

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_IDLE) {
                    ui.removeCallbacks(hidePinned);
                    ui.postDelayed(hidePinned, 1200);
                } else {
                    ui.removeCallbacks(hidePinned);
                    pinnedDate.setVisibility(View.VISIBLE);
                }
            }
            public void onScroll(AbsListView view, int first, int visible, int total) {
                fab.setVisibility(total > 0 && first + visible < total - 2 ? View.VISIBLE : View.GONE);
                if (first >= 0 && first < rows.size()) {
                    int mi = rows.get(first)[1];
                    pinnedDate.setText(msgs.get(mi).dateLabel);
                }
            }
        });
    }

    final Runnable hidePinned = () -> pinnedDate.setVisibility(View.GONE);

    View headerIcon(String emojiId, View.OnClickListener cb) {
        ImageView iv = new ImageView(this);
        Drawable d = emojiDrawable(emojiId, false);
        if (d != null) iv.setImageDrawable(d);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        iv.setOnClickListener(cb);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return iv;
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    // ---------------- data loading ----------------
    void loadData() {
        new Thread(() -> {
            try {
                String json = Prep.readText(new File(chatDir, "data.json"));
                JSONObject rootObj = new JSONObject(json);
                meta = rootObj.getJSONObject("meta");
                JSONArray arr = rootObj.getJSONArray("msgs");
                String lastDate = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Msg m = new Msg();
                    m.dateLabel = o.getString("d");
                    m.iso = o.getString("iso");
                    m.time = o.getString("t");
                    m.sender = o.getInt("s");
                    String k = o.getString("k");
                    m.kind = k.equals("sys") ? K_SYS : k.equals("photo") ? K_PHOTO : k.equals("sticker") ? K_STICKER
                            : k.equals("video") ? K_VIDEO : k.equals("audio") ? K_AUDIO : k.equals("doc") ? K_DOC
                            : k.equals("docmiss") ? K_DOCMISS : k.equals("call") ? K_CALL : K_TEXT;
                    m.file = o.optString("f", null);
                    if (m.file != null && m.file.length() == 0) m.file = null;
                    m.text = o.optString("x", "");
                    m.name = o.optString("n", null);
                    m.omitted = o.optInt("o", 0) == 1;
                    m.waDeleted = o.optInt("del", 0) == 1;
                    m.jumbo = o.optInt("j", 0) == 1;
                    JSONArray e = o.optJSONArray("e");
                    if (e != null && e.length() > 0) {
                        m.spanStart = new int[e.length()];
                        m.spanEnd = new int[e.length()];
                        m.spanId = new String[e.length()];
                        for (int j = 0; j < e.length(); j++) {
                            JSONArray s = e.getJSONArray(j);
                            m.spanStart[j] = s.getInt(0);
                            m.spanEnd[j] = s.getInt(1);
                            m.spanId[j] = s.getString(2);
                        }
                    }
                    JSONArray w = o.optJSONArray("w");
                    if (w != null) {
                        m.dur = (float) w.getDouble(0);
                        String peaks = w.getString(1);
                        m.peaks = new int[peaks.length()];
                        for (int j = 0; j < peaks.length(); j++) m.peaks[j] = Character.digit(peaks.charAt(j), 36);
                    }
                    msgs.add(m);
                    if (!m.dateLabel.equals(lastDate)) {
                        lastDate = m.dateLabel;
                        rows.add(new int[]{ROW_SEP, i});
                        dayIso.add(m.iso);
                        dayLabel.add(m.dateLabel);
                        dayFirstRow.add(rows.size() - 1);
                    }
                    rows.add(new int[]{ROW_MSG, i});
                }
                msgRow = new int[msgs.size()];
                for (int r = 0; r < rows.size(); r++) if (rows.get(r)[0] == ROW_MSG) msgRow[rows.get(r)[1]] = r;
                lowerTexts = new String[msgs.size()];
                for (int i = 0; i < msgs.size(); i++) lowerTexts[i] = msgs.get(i).text.toLowerCase(Locale.ROOT);
                ui.post(() -> {
                    friendNameSpan = buildNameSpan();
                    if (headerNameView != null) headerNameView.setText(friendNameSpan);
                    if (subtitleView != null) {
                        try { subtitleView.setText(meta.getString("firstLabel") + " \u2013 " + meta.getString("lastLabel")); } catch (Exception ignored) { }
                    }
                    adapter = new ChatAdapter();
                    listView.setAdapter(adapter);
                    dataReady = true;
                    if (pendingJumpMsg >= 0) { int j = pendingJumpMsg; pendingJumpMsg = -1; applyJumpMsg(j); }
                    else if (pendingJumpDay != null) { String dj = pendingJumpDay; pendingJumpDay = null; applyJumpDay(dj); }
                    else listView.setSelection(rows.size() - 1);
                    loadingOverlay.setVisibility(View.GONE);
                });
            } catch (Exception ex) {
                ui.post(() -> Toast.makeText(this, "Chat load error: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    String readAssetText(String path) throws Exception {
        InputStream is = getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    Spannable buildNameSpan() {
        try {
            String name = meta.getString("friendName");
            JSONArray fe = meta.getJSONArray("friendEmo");
            SpannableString ss = new SpannableString(name);
            for (int j = 0; j < fe.length(); j++) {
                JSONArray s = fe.getJSONArray(j);
                Drawable d = emojiDrawable(s.getString(2), false);
                if (d != null) ss.setSpan(new ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), s.getInt(0), s.getInt(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return ss;
        } catch (Exception e) {
            return new SpannableString(chatTitle);
        }
    }

    // ---------------- emoji ----------------
    Drawable emojiDrawable(String id, boolean jumbo) {
        HashMap<String, Drawable> cache = jumbo ? emojiJumbo : emojiInline;
        if (cache.containsKey(id)) return cache.get(id);
        Drawable d = null;
        int px = dp(jumbo ? 59 : 29);
        if (d == null) {
            try {
                InputStream is = getAssets().open("emoji/" + id + ".png");
                Bitmap bm = BitmapFactory.decodeStream(is);
                is.close();
                if (bm != null) {
                    BitmapDrawable bd = new BitmapDrawable(getResources(), bm);
                    bd.setBounds(0, 0, px, px);
                    d = bd;
                }
            } catch (Exception ignored) { }
        }
        cache.put(id, d);
        return d;
    }

    Spannable spannableFor(int msgIndex, Msg m, boolean jumbo) {
        int key = msgIndex * 2 + (jumbo ? 1 : 0);
        Spannable cached = spanCache.get(key);
        if (cached != null) return cached;
        SpannableString ss = new SpannableString(m.text);
        if (m.spanId != null) {
            for (int j = 0; j < m.spanId.length; j++) {
                Drawable d = emojiDrawable(m.spanId[j], jumbo);
                if (d != null) {
                    try {
                        ss.setSpan(new ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), m.spanStart[j], m.spanEnd[j], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception ignored) { }
                }
            }
        }
        spanCache.put(key, ss);
        return ss;
    }

    // ---------------- images / settings ----------------
    void stopAnim(ImageView iv) {
        Drawable d = iv.getDrawable();
        if (d instanceof AnimatedImageDrawable) { try { ((AnimatedImageDrawable) d).stop(); } catch (Exception ignored) { } }
    }

    // Stickers are .webp; 49 of the 66 are animated. BitmapFactory only ever shows
    // the first frame, so animated stickers go through ImageDecoder instead.
    void loadSticker(ImageView iv, String assetPath, String key) {
        if (Build.VERSION.SDK_INT < 28) { loadThumb(iv, assetPath, key); return; }
        stopAnim(iv);
        iv.setImageDrawable(null);
        iv.setTag(key);
        ioExec.execute(() -> {
            try {
                InputStream is = new java.io.FileInputStream(new File(chatDir, assetPath));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bos.toByteArray())));
                ui.post(() -> {
                    if (!key.equals(iv.getTag())) return;
                    iv.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) { try { ((AnimatedImageDrawable) d).start(); } catch (Exception ignored) { } }
                });
            } catch (Exception e) {
                ui.post(() -> { if (key.equals(iv.getTag())) loadThumb(iv, assetPath, key); });
            }
        });
    }

    void loadThumb(ImageView iv, String assetPath, String key) {
        stopAnim(iv);
        Bitmap bm = bmpCache.get(key);
        if (bm != null) { iv.setImageBitmap(bm); return; }
        iv.setImageBitmap(null);
        iv.setTag(key);
        ioExec.execute(() -> {
            try {
                InputStream is = new java.io.FileInputStream(new File(chatDir, assetPath));
                Bitmap b = BitmapFactory.decodeStream(is);
                is.close();
                if (b != null) {
                    bmpCache.put(key, b);
                    ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(b); });
                }
            } catch (Exception ignored) { }
        });
    }

    Bitmap dpThumbBitmap() { return avatarFor(chatTitle); }

    void loadAvatar() {
        if (dpView != null) dpView.setImageBitmap(avatarFor(chatTitle));
    }

    void loadLibMeta() {
        try {
            JSONObject mj = new JSONObject(Prep.readText(new File(chatDir, "meta.json")));
            chatTitle = mj.optString("title", "Chat");
            meName = mj.optString("me", "");
            groupChat = mj.optBoolean("group", false);
        } catch (Exception ignored) { }
    }

    int nameColor(String name) {
        int[] cols = {0xFFFF9F43, 0xFF53BDEB, 0xFF7ED321, 0xFFFF6B9D, 0xFFB388FF, 0xFF4DD0E1, 0xFFFFD54F, 0xFFAED581};
        return cols[Math.abs(name.hashCode()) % cols.length];
    }

    // Letter avatar: coloured circle with the first letter of the name.
    Bitmap avatarFor(String name) {
        String key = name == null || name.length() == 0 ? "?" : name;
        Bitmap cached = avatarCache.get(key);
        if (cached != null) return cached;
        int sz = dp(76);
        Bitmap bm = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] palette = {0xFF00A884, 0xFF008069, 0xFF128C7E, 0xFF075E54, 0xFF34B7F1, 0xFF7350BA, 0xFFE91E63, 0xFFFF9800, 0xFF5C6BC0, 0xFF26A69A};
        paint.setColor(palette[Math.abs(key.hashCode()) % palette.length]);
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(sz * 0.46f);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        String letter = "?";
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isLetterOrDigit(ch)) { letter = String.valueOf(Character.toUpperCase(ch)); break; }
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        cv.drawText(letter, sz / 2f, sz / 2f - (fm.ascent + fm.descent) / 2f, paint);
        avatarCache.put(key, bm);
        return bm;
    }

    Bitmap myDpThumbBitmap() { return avatarFor(meName.length() > 0 ? meName : "You"); }

    Bitmap micBitmapCache;
    Bitmap micBitmap() {
        if (micBitmapCache != null) return micBitmapCache;
        try {
            InputStream is = getAssets().open("emoji/1f3a4.png");
            micBitmapCache = BitmapFactory.decodeStream(is);
            is.close();
        } catch (Exception ignored) { }
        return micBitmapCache;
    }

    void applyWallpaper() {
        if (!"photo".equals(theme)) {
            bgImage.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            if (listView != null) listView.setBackgroundColor(cBg);
            return;
        }
        File f = new File(chatDir, "bg.jpg");
        if (f.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm != null) {
                bgImage.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
                int W = getResources().getDisplayMetrics().widthPixels;
                int H = getResources().getDisplayMetrics().heightPixels;
                Bitmap cropped = centerCropTo(bm, W, H);
                LayerDrawable ld = new LayerDrawable(new Drawable[]{ new BitmapDrawable(getResources(), cropped), new ColorDrawable(0xC4040C09) });
                if (listView != null) listView.setBackground(ld);
            }
        }
    }

    Bitmap centerCropTo(Bitmap src, int tw, int th) {
        float scale = Math.max((float) tw / src.getWidth(), (float) th / src.getHeight());
        int sw = Math.max(1, Math.round(src.getWidth() * scale));
        int sh = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        int cw = Math.min(tw, sw), ch = Math.min(th, sh);
        return Bitmap.createBitmap(scaled, (sw - cw) / 2, (sh - ch) / 2, cw, ch);
    }

    void addMediaFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, 50);
    }

    String displayNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 50 || res != RESULT_OK || data == null) return;
        final java.util.List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        Toast.makeText(this, "Media add ho raha hai\u2026", Toast.LENGTH_SHORT).show();
        ioExec.execute(() -> {
            int copied = 0;
            File mediaDir = new File(chatDir, "media");
            mediaDir.mkdirs();
            for (Uri u : uris) {
                String name = displayNameOf(u);
                if (name == null) continue;
                name = name.replace("\\", "/");
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                try {
                    InputStream is = getContentResolver().openInputStream(u);
                    FileOutputStream fos = new FileOutputStream(new File(mediaDir, name));
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    is.close();
                    copied++;
                } catch (Exception ignored) { }
            }
            int enriched = Prep.enrich(chatDir);
            final int cp = copied;
            ui.post(() -> {
                Toast.makeText(this, cp + " files add ho gayin", Toast.LENGTH_SHORT).show();
                if (enriched > 0) recreate();
            });
        });
    }

    // ---------------- waveform view ----------------
    static class WaveformView extends View {
        int[] peaks;
        float progress;
        int playedColor = 0xFFFFFFFF, unplayedColor = 0x66FFFFFF;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        interface SeekCb { void onSeek(float frac); }
        SeekCb seekCb;
        WaveformView(Context c) { super(c); }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (peaks == null || peaks.length == 0) return;
            int n = peaks.length;
            float slot = (float) getWidth() / n;
            float barW = Math.max(2f, slot * 0.55f);
            float h = getHeight();
            for (int pass = 0; pass < 2; pass++) {
                paint.setColor(pass == 0 ? unplayedColor : playedColor);
                for (int i = 0; i < n; i++) {
                    boolean played = (i + 0.5f) / n <= progress;
                    if ((pass == 1) != played) continue;
                    float bh = Math.max(3f, (3f + peaks[i]) / 36f * h);
                    float x = i * slot + (slot - barW) / 2f;
                    canvas.drawRect(x, (h - bh) / 2f, x + barW, (h + bh) / 2f, paint);
                }
            }
        }
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false; // display only: playback starts from the play button; taps and scrolls pass through
        }
    }

    // ---------------- adapter ----------------
    static class RowViews {
        FrameLayout bubbleF, mediaFrame;
        LinearLayout content, metaRow, audioRow;
        TextView body, time, ticks, omitted, docTv, playBtn, durTv, spdBtn, playOverlay, timePill, starTv, nameTv;
        ImageView img, dpThumb;
        WaveformView wave;
        View spacerL, spacerR;
    }

    class ChatAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int p) { return rows.get(p); }
        public long getItemId(int p) { return p; }
        public int getViewTypeCount() { return 3; }
        public int getItemViewType(int p) {
            int[] r = rows.get(p);
            if (r[0] == ROW_SEP) return 0;
            return msgs.get(r[1]).kind == K_SYS ? 1 : 2;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            int type = getItemViewType(pos);
            if (type == 0) return sepView(pos, cv);
            if (type == 1) return sysView(pos, cv);
            return msgView(pos, cv);
        }

        View sepView(int pos, View cv) {
            TextView chip;
            if (cv instanceof TextView) chip = (TextView) cv;
            else chip = new TextView(ChatActivity.this);
            Msg m = msgs.get(rows.get(pos)[1]);
            chip.setText(m.dateLabel);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            boolean hl = pos == highlightRow;
            chip.setTextColor(hl ? 0xFFFFFFFF : cChipText);
            chip.setBackground(roundRect(hl ? 0xFF00A884 : cChip, 8));
            chip.setPadding(dp(14), dp(5), dp(14), dp(5));
            LinearLayout wrap = new LinearLayout(ChatActivity.this);
            wrap.setGravity(Gravity.CENTER);
            wrap.setPadding(0, dp(10), 0, dp(6));
            wrap.addView(chip);
            return wrap;
        }

        View sysView(int pos, View cv) {
            TextView t;
            if (cv instanceof TextView) t = (TextView) cv; else t = new TextView(ChatActivity.this);
            Msg m = msgs.get(rows.get(pos)[1]);
            t.setText(m.spanId != null ? spannableFor(rows.get(pos)[1], m, false) : m.text);
            t.setTextSize(12.5f);
            boolean enc = m.text.contains("end-to-end encrypted");
            t.setTextColor(enc && !light ? 0xFFFFD279 : cChipText);
            t.setBackground(roundRect(cChip, 8));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout wrap = new LinearLayout(ChatActivity.this);
            wrap.setGravity(Gravity.CENTER);
            wrap.setPadding(dp(28), dp(6), dp(28), dp(6));
            wrap.addView(t, new LinearLayout.LayoutParams(-2, -2));
            return wrap;
        }

        RowViews buildMsgView() {
            RowViews h = new RowViews();
            LinearLayout root = new LinearLayout(ChatActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(dp(8), dp(3), dp(8), dp(3));
            h.spacerL = new View(ChatActivity.this);
            h.spacerR = new View(ChatActivity.this);
            FrameLayout wrapF = new FrameLayout(ChatActivity.this);
            h.bubbleF = new FrameLayout(ChatActivity.this);
            h.content = new LinearLayout(ChatActivity.this);
            h.content.setOrientation(LinearLayout.VERTICAL);
            // media (photo / video / sticker) with time pill overlay
            h.mediaFrame = new FrameLayout(ChatActivity.this);
            h.img = new ImageView(ChatActivity.this);
            h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.img.setClipToOutline(true);
            h.img.setOutlineProvider(new ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8)); }
            });
            h.mediaFrame.addView(h.img, new FrameLayout.LayoutParams(-1, -1));
            h.playOverlay = tv("\u25B6", 20, 0xFFFFFFFF);
            h.playOverlay.setGravity(Gravity.CENTER);
            GradientDrawable ov = new GradientDrawable();
            ov.setShape(GradientDrawable.OVAL);
            ov.setColor(0x99000000);
            h.playOverlay.setBackground(ov);
            FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(dp(52), dp(52));
            olp.gravity = Gravity.CENTER;
            h.mediaFrame.addView(h.playOverlay, olp);
            h.timePill = tv("", 11, 0xFFFFFFFF);
            h.timePill.setBackground(roundRect(0x66000000, 8));
            h.timePill.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams tpl = new FrameLayout.LayoutParams(-2, -2);
            tpl.gravity = Gravity.BOTTOM | Gravity.END;
            tpl.rightMargin = dp(6);
            tpl.bottomMargin = dp(6);
            h.mediaFrame.addView(h.timePill, tpl);
            h.nameTv = tv("", 13, cName);
            h.nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            h.nameTv.setPadding(dp(2), dp(1), dp(2), dp(3));
            h.content.addView(h.nameTv);
            h.content.addView(h.mediaFrame);
            // audio row
            h.audioRow = new LinearLayout(ChatActivity.this);
            h.audioRow.setOrientation(LinearLayout.HORIZONTAL);
            h.audioRow.setGravity(Gravity.CENTER_VERTICAL);
            h.dpThumb = new ImageView(ChatActivity.this);
            h.dpThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.dpThumb.setClipToOutline(true);
            h.dpThumb.setOutlineProvider(new ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            FrameLayout dpWrap = new FrameLayout(ChatActivity.this);
            dpWrap.addView(h.dpThumb, new FrameLayout.LayoutParams(-1, -1));
            ImageView micBadge = new ImageView(ChatActivity.this);
            GradientDrawable mbg = new GradientDrawable();
            mbg.setShape(GradientDrawable.OVAL);
            mbg.setColor(0xFFFFFFFF);
            micBadge.setBackground(mbg);
            micBadge.setPadding(dp(3), dp(3), dp(3), dp(3));
            Bitmap micBm = micBitmap();
            if (micBm != null) micBadge.setImageBitmap(micBm);
            FrameLayout.LayoutParams mbl = new FrameLayout.LayoutParams(dp(17), dp(17));
            mbl.gravity = Gravity.BOTTOM | Gravity.END;
            dpWrap.addView(micBadge, mbl);
            LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(dp(38), dp(38));
            dtl.rightMargin = dp(8);
            h.audioRow.addView(dpWrap, dtl);
            h.playBtn = tv("\u25B6", 27, 0xFFFFFFFF);
            h.playBtn.setGravity(Gravity.CENTER);
            h.audioRow.addView(h.playBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
            h.wave = new WaveformView(ChatActivity.this);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, dp(34), 1);
            wlp.leftMargin = dp(9);
            wlp.rightMargin = dp(9);
            h.audioRow.addView(h.wave, wlp);
            LinearLayout side = new LinearLayout(ChatActivity.this);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setGravity(Gravity.CENTER);
            h.durTv = tv("", 12, cDim);
            h.durTv.setGravity(Gravity.CENTER);
            side.addView(h.durTv);
            h.spdBtn = tv("1x", 11, cText);
            h.spdBtn.setTypeface(Typeface.DEFAULT_BOLD);
            h.spdBtn.setGravity(Gravity.CENTER);
            h.spdBtn.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.topMargin = dp(3);
            slp.gravity = Gravity.CENTER;
            side.addView(h.spdBtn, slp);
            h.audioRow.addView(side);
            h.content.addView(h.audioRow);
            h.docTv = tv("", 14, cText);
            h.docTv.setPadding(dp(10), dp(9), dp(10), dp(9));
            h.content.addView(h.docTv);
            h.body = tv("", 15.5f, cText);
            h.body.setLineSpacing(0, 1.15f);
            h.content.addView(h.body);
            h.omitted = tv("", 13, cDim);
            h.omitted.setGravity(Gravity.CENTER);
            h.omitted.setPadding(dp(6), dp(4), dp(6), dp(4));
            h.content.addView(h.omitted);
            h.metaRow = new LinearLayout(ChatActivity.this);
            h.metaRow.setOrientation(LinearLayout.HORIZONTAL);
            h.metaRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(3);
            h.metaRow.setLayoutParams(mlp);
            h.starTv = tv("\u2605 ", 11, 0xFFF2B01E);
            h.metaRow.addView(h.starTv);
            h.time = tv("", 11, cDim);
            h.metaRow.addView(h.time);
            h.ticks = tv(" \u2713\u2713", 11, 0xFF53BDEB);
            h.metaRow.addView(h.ticks);
            h.content.addView(h.metaRow);
            h.bubbleF.addView(h.content, new FrameLayout.LayoutParams(-2, -2));
            wrapF.addView(h.bubbleF, new FrameLayout.LayoutParams(-2, -2));
            root.addView(h.spacerL, new LinearLayout.LayoutParams(0, -2, 0));
            root.addView(wrapF, new LinearLayout.LayoutParams(0, -2, 84));
            root.addView(h.spacerR, new LinearLayout.LayoutParams(0, -2, 16));
            root.setTag(h);
            return h;
        }

        Spannable pillText(Msg m, boolean mine, boolean star) {
            String s = m.time + (star ? " \u2605" : "") + (mine ? " \u2713\u2713" : "");
            SpannableString ss = new SpannableString(s);
            if (mine) ss.setSpan(new ForegroundColorSpan(0xFF53BDEB), s.length() - 2, s.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ss;
        }

        Spannable callSpannable() {
            String s = "\uD83D\uDCDE Call";
            SpannableString ss = new SpannableString(s);
            Drawable d = emojiDrawable("1f4de", false);
            if (d != null) ss.setSpan(new ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ss;
        }

        View msgView(int pos, View cv) {
            RowViews h;
            LinearLayout root;
            if (cv != null && cv.getTag() instanceof RowViews) {
                h = (RowViews) cv.getTag();
                root = (LinearLayout) cv;
            } else {
                h = buildMsgView();
                root = (LinearLayout) h.bubbleF.getParent().getParent();
            }
            final int msgIndex = rows.get(pos)[1];
            final Msg m = msgs.get(msgIndex);
            boolean mine = m.sender == 0;
            boolean isDeleted = deleted.contains(String.valueOf(msgIndex));
            LinearLayout.LayoutParams lpL = (LinearLayout.LayoutParams) h.spacerL.getLayoutParams();
            LinearLayout.LayoutParams lpR = (LinearLayout.LayoutParams) h.spacerR.getLayoutParams();
            float wl = mine ? 16 : 0, wr = mine ? 0 : 16;
            if (lpL.weight != wl || lpR.weight != wr) {
                lpL.weight = wl;
                lpR.weight = wr;
                h.spacerL.setLayoutParams(lpL);
                h.spacerR.setLayoutParams(lpR);
            }
            FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) h.bubbleF.getLayoutParams();
            blp.gravity = mine ? Gravity.END : Gravity.START;
            h.bubbleF.setLayoutParams(blp);

            boolean mediaKind = m.kind == K_PHOTO || m.kind == K_VIDEO || m.kind == K_STICKER;
            boolean hasText = m.text != null && m.text.length() > 0 && !isDeleted && !m.waDeleted;
            boolean plain = (m.kind == K_STICKER || (m.jumbo && mediaKind == false)) && !isDeleted && !m.waDeleted;
            if (m.jumbo && !isDeleted && !m.waDeleted) plain = true;
            if (plain) {
                h.bubbleF.setBackground(null);
                h.content.setPadding(0, 0, 0, 0);
            } else {
                GradientDrawable bg = bubbleBg(mine ? cMine : cTheirs, mine);
                if (msgIndex == highlightMsg) bg.setStroke(dp(2), 0xFF00A884);
                h.bubbleF.setBackground(bg);
                if (m.kind == K_PHOTO || m.kind == K_VIDEO) h.content.setPadding(dp(3), dp(3), dp(3), hasText ? dp(6) : dp(3));
                else if (m.kind == K_AUDIO) h.content.setPadding(dp(8), dp(6), dp(8), dp(6));
                else h.content.setPadding(dp(9), dp(6), dp(9), dp(7));
            }
            int sideText = mine ? cMineText : cText;
            int sideDim;
            if (mine) sideDim = (light && "default".equals(bubbleKey)) ? 0xFF5B7A63 : 0xB3FFFFFF;
            else sideDim = cDim;

            h.mediaFrame.setVisibility(View.GONE);
            h.audioRow.setVisibility(View.GONE);
            h.docTv.setVisibility(View.GONE);
            h.body.setVisibility(View.GONE);
            h.omitted.setVisibility(View.GONE);
            h.metaRow.setVisibility(View.VISIBLE);
            h.starTv.setVisibility(starred.contains(String.valueOf(msgIndex)) ? View.VISIBLE : View.GONE);
            h.timePill.setVisibility(View.GONE);
            if (groupChat && !mine && !isDeleted && m.name != null) {
                h.nameTv.setVisibility(View.VISIBLE);
                h.nameTv.setText(m.name);
                h.nameTv.setTextColor(nameColor(m.name));
            } else h.nameTv.setVisibility(View.GONE);

            if (isDeleted || m.waDeleted) {
                h.body.setVisibility(View.VISIBLE);
                h.body.setText(deletedPlaceholder());
                h.body.setTextColor(sideDim);
                h.body.setTextSize(14);
            } else if (m.kind == K_PHOTO || m.kind == K_VIDEO) {
                h.mediaFrame.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) h.mediaFrame.getLayoutParams();
                flp.width = dp(250);
                flp.height = dp(200);
                h.mediaFrame.setLayoutParams(flp);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.playOverlay.setVisibility(m.kind == K_VIDEO ? View.VISIBLE : View.GONE);
                loadThumb(h.img, "thumbs/" + m.file + ".jpg", "th:" + m.file);
                final String file = m.file;
                final int kind = m.kind;
                h.img.setOnClickListener(v -> {
                    Intent it = new Intent(ChatActivity.this, kind == K_VIDEO ? VideoActivity.class : PhotoActivity.class);
                    it.putExtra("file", file);
                    it.putExtra("msgIndex", msgIndex);
                    it.putExtra("label", m.dateLabel + " \u2022 " + m.time);
                    it.putExtra("chatDir", chatDir.getAbsolutePath());
                    startActivity(it);
                });
                if (hasText) showBody(h, msgIndex, m, sideText);
                else {
                    h.timePill.setVisibility(View.VISIBLE);
                    h.timePill.setText(pillText(m, mine, starred.contains(String.valueOf(msgIndex))));
                    h.metaRow.setVisibility(View.GONE);
                }
            } else if (m.kind == K_STICKER) {
                h.mediaFrame.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) h.mediaFrame.getLayoutParams();
                flp.width = dp(148);
                flp.height = dp(148);
                h.mediaFrame.setLayoutParams(flp);
                h.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.playOverlay.setVisibility(View.GONE);
                h.img.setOnClickListener(null);
                loadSticker(h.img, "media/" + m.file, "st:" + m.file);
                if (hasText) showBody(h, msgIndex, m, sideText);
                else {
                    h.timePill.setVisibility(View.VISIBLE);
                    h.timePill.setText(pillText(m, mine, starred.contains(String.valueOf(msgIndex))));
                    h.metaRow.setVisibility(View.GONE);
                }
            } else if (m.kind == K_AUDIO) {
                h.audioRow.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) h.audioRow.getLayoutParams();
                alp.width = dp(252);
                alp.height = -2;
                h.audioRow.setLayoutParams(alp);
                bindAudio(h, msgIndex, m, mine, sideText, sideDim);
            } else if (m.kind == K_CALL) {
                h.body.setVisibility(View.VISIBLE);
                h.body.setText(callSpannable());
                h.body.setTextColor(sideText);
                h.body.setTextSize(15.5f);
            } else if (m.kind == K_DOC || m.kind == K_DOCMISS) {
                h.docTv.setVisibility(View.VISIBLE);
                h.docTv.setText("\uD83D\uDCC4 " + (m.file != null ? m.file : "Document") + (m.kind == K_DOCMISS ? "\n(media not in export)" : ""));
                h.docTv.setTextColor(sideText);
                h.docTv.setBackground(roundRect(0x22808080, 8));
                if (hasText && !m.text.equals(m.file)) showBody(h, msgIndex, m, sideText);
            } else {
                if (hasText) showBody(h, msgIndex, m, sideText);
            }
            if (m.omitted && !isDeleted && !m.waDeleted) {
                h.omitted.setVisibility(View.VISIBLE);
                h.omitted.setText("\uD83D\uDCCE Media omitted (not in original export)");
                h.omitted.setTextColor(sideDim);
            }

            h.time.setText(m.time);
            h.time.setTextColor(sideDim);
            h.ticks.setVisibility(mine ? View.VISIBLE : View.GONE);
            h.bubbleF.setOnLongClickListener(v -> { showMsgDialog(msgIndex); return true; });
            return root;
        }

        void showBody(RowViews h, int msgIndex, Msg m, int color) {
            h.body.setVisibility(View.VISIBLE);
            h.body.setTextSize(15.5f);
            h.body.setTextColor(color);
            h.body.setText(spannableFor(msgIndex, m, m.jumbo));
        }

        void bindAudio(RowViews h, final int msgIndex, Msg m, boolean mine, int sideText, int sideDim) {
            boolean isPlayingThis = msgIndex == playingMsg && audioState != 0;
            h.dpThumb.setVisibility(View.VISIBLE);
            Bitmap db = mine ? myDpThumbBitmap() : avatarFor(m.name != null ? m.name : chatTitle);
            if (db != null) h.dpThumb.setImageBitmap(db);
            boolean playingNow = isPlayingThis && audioState == 1;
            int playCol;
            if (!light) playCol = 0xFFFFFFFF;
            else if (mine && !"default".equals(bubbleKey)) playCol = 0xFFFFFFFF;
            else playCol = 0xFF54656F;
            h.playBtn.setBackground(null);
            h.playBtn.setTextColor(playCol);
            h.playBtn.setText(playingNow ? "\u275A\u275A" : "\u25B6");
            h.playBtn.setTextSize(playingNow ? 19 : 27);
            h.playBtn.setTag(msgIndex);
            h.playBtn.setOnClickListener(v -> toggleAudio(msgIndex));
            h.wave.peaks = m.peaks;
            if (mine) {
                if (light && "default".equals(bubbleKey)) { h.wave.playedColor = 0xFF00705A; h.wave.unplayedColor = 0x7300705A; }
                else { h.wave.playedColor = 0xFFFFFFFF; h.wave.unplayedColor = 0x59FFFFFF; }
            }
            else if (light) { h.wave.playedColor = 0xFF00A884; h.wave.unplayedColor = 0x40000000; }
            else { h.wave.playedColor = 0xFF53BDEB; h.wave.unplayedColor = 0x40FFFFFF; }
            float frac = 0f;
            if (isPlayingThis) {
                frac = audioDurMs > 0 ? (float) audioPosMs / audioDurMs : 0f;
                h.durTv.setText(fmtDur(audioPosMs / 1000f));
            } else {
                h.durTv.setText(fmtDur(m.dur));
            }
            h.wave.progress = frac;
            h.wave.setTag(msgIndex);
            h.wave.seekCb = null;
            h.wave.invalidate();
            h.durTv.setTextColor(sideDim);
            Float r = rates.get(msgIndex);
            h.spdBtn.setText((r == null ? "1" : trimRate(r)) + "x");
            h.spdBtn.setTextColor(sideText);
            h.spdBtn.setBackground(roundRect(0x2E808080, 9));
            h.spdBtn.setTag(msgIndex);
            h.spdBtn.setOnClickListener(v -> cycleRate(msgIndex));
        }
    }

    Spannable deletedPlaceholder() {
        String s = "\uD83D\uDEAB This message was deleted";
        SpannableString ss = new SpannableString(s);
        Drawable d = emojiDrawable("1f6ab", false);
        if (d != null) ss.setSpan(new ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    String fmtDur(float sec) {
        int s = Math.round(sec);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    String trimRate(float r) {
        return r == (int) r ? String.valueOf((int) r) : String.valueOf(r);
    }

    // ---------------- audio playback ----------------
    final HashMap<Integer, Float> rates = new HashMap<>();
    float pendingSeek = -1f;

    // v7 player: a FRESH MediaPlayer is created for every note and released when
    // done. The app keeps its own state (audioState / audioPosMs) and its own
    // clock (SystemClock), so the button, seconds and wave are driven by taps and
    // time — never by player.isPlaying() / getCurrentPosition().
    float curRate() {
        Float r = playingMsg >= 0 ? rates.get(playingMsg) : null;
        return r == null ? 1f : r;
    }

    // Bank the time elapsed since the last stamp into audioPosMs (rate-aware).
    void syncPos() {
        if (audioState != 1) return;
        long now = SystemClock.elapsedRealtime();
        long dt = now - audioStampMs;
        if (dt > 0) {
            audioPosMs += (long) (dt * curRate());
            if (audioDurMs > 0 && audioPosMs > audioDurMs) audioPosMs = audioDurMs;
        }
        audioStampMs = now;
    }

    void releasePlayer() {
        stopTicker();
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    void finishAudio() {
        int done = playingMsg;
        releasePlayer();
        playingMsg = -1;
        audioState = 0;
        audioPosMs = 0;
        audioDurMs = 0;
        if (done >= 0) updateAudioRow(done);
    }

    void startAudio(final int msgIndex) {
        Msg m = msgs.get(msgIndex);
        if (m.file == null) return;
        int prevPlaying = playingMsg;
        releasePlayer();
        // State first, player second: the row flips to ❚❚ from the tap itself,
        // before the player has even prepared.
        playingMsg = msgIndex;
        audioState = 1;
        audioPosMs = 0;
        audioDurMs = (int) (m.dur * 1000f);
        audioStampMs = SystemClock.elapsedRealtime();
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            try { p.setPlaybackParams(p.getPlaybackParams().setSpeed(curRate())); } catch (Exception ignored) { }
            try { p.start(); } catch (Exception ignored) { }
            audioStampMs = SystemClock.elapsedRealtime();
            startTicker();
            updateAudioRow(playingMsg);
        });
        mp.setOnCompletionListener(p -> { if (p == player) finishAudio(); });
        mp.setOnErrorListener((p, what, extra) -> { if (p == player) finishAudio(); return true; });
        if (prevPlaying >= 0 && prevPlaying != msgIndex) updateAudioRow(prevPlaying);
        updateAudioRow(msgIndex);
        try {
            mp.setDataSource(new File(new File(chatDir, "media"), m.file).getAbsolutePath());
            mp.prepareAsync();
        } catch (Exception e) {
            finishAudio();
        }
    }

    void toggleAudio(int msgIndex) {
        if (msgIndex == playingMsg && player != null && audioState != 0) {
            // Pause/resume is decided by OUR state, not player.isPlaying().
            if (audioState == 1) {
                syncPos();
                try { player.pause(); } catch (Exception ignored) { }
                audioState = 2;
                stopTicker();
            } else {
                try { player.start(); } catch (Exception ignored) { }
                audioState = 1;
                audioStampMs = SystemClock.elapsedRealtime();
                startTicker();
            }
            updateAudioRow(msgIndex);
            return;
        }
        startAudio(msgIndex);
    }

    void cycleRate(int msgIndex) {
        Float cur = rates.get(msgIndex);
        float next = cur == null ? 1.5f : cur == 1f ? 1.5f : cur == 1.5f ? 2f : 1f;
        if (msgIndex == playingMsg) syncPos(); // bank elapsed time at the old rate first
        rates.put(msgIndex, next);
        if (msgIndex == playingMsg && player != null && audioState != 0) {
            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(next)); } catch (Exception ignored) { }
        }
        updateAudioRow(msgIndex);
    }

    void startTicker() {
        stopTicker();
        progressTick = new Runnable() {
            public void run() {
                if (audioState == 1 && playingMsg >= 0) {
                    syncPos();
                    updatePlayingRow();
                    ui.postDelayed(this, 100);
                }
            }
        };
        ui.postDelayed(progressTick, 100);
    }

    void stopTicker() {
        if (progressTick != null) ui.removeCallbacks(progressTick);
        progressTick = null;
    }

    // Update ONLY the visible widgets of one audio row (button glyph, wave, duration, rate).
    // Never rebinds the row, so playback state changes cannot flicker or move the list.
    void updateAudioRow(int msgIndex) {
        if (adapter == null || msgIndex < 0 || msgIndex >= msgs.size()) return;
        int row = msgRow[msgIndex];
        int first = listView.getFirstVisiblePosition();
        int last = listView.getLastVisiblePosition();
        if (row < first || row > last) return;
        View child = listView.getChildAt(row - first);
        if (child == null || !(child.getTag() instanceof RowViews)) return;
        RowViews h = (RowViews) child.getTag();
        if (h.audioRow == null || h.audioRow.getVisibility() != View.VISIBLE) return;
        boolean isCur = msgIndex == playingMsg && audioState != 0;
        boolean playingNow = isCur && audioState == 1;
        h.playBtn.setText(playingNow ? "\u275A\u275A" : "\u25B6");
        h.playBtn.setTextSize(playingNow ? 19 : 27);
        float frac = 0f;
        if (isCur) {
            if (audioDurMs > 0) frac = (float) audioPosMs / audioDurMs;
            h.durTv.setText(fmtDur(audioPosMs / 1000f));
        } else {
            h.durTv.setText(fmtDur(msgs.get(msgIndex).dur));
        }
        h.wave.progress = frac;
        h.wave.invalidate();
        Float r = rates.get(msgIndex);
        h.spdBtn.setText((r == null ? "1" : trimRate(r)) + "x");
    }

    void updatePlayingRow() {
        updateAudioRow(playingMsg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && audioState == 1) {
            syncPos();
            try { player.pause(); } catch (Exception ignored) { }
            audioState = 2;
            stopTicker();
            updateAudioRow(playingMsg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
    }

    // ---------------- jump intents (v9: Go to message / On this day) ----------------
    boolean dataReady = false;
    int pendingJumpMsg = -1;
    String pendingJumpDay = null;

    void handleJumpIntent(Intent it) {
        if (it == null) return;
        if (it.hasExtra("jumpMsg")) {
            int idx = it.getIntExtra("jumpMsg", -1);
            it.removeExtra("jumpMsg");
            if (idx >= 0) { if (dataReady) applyJumpMsg(idx); else pendingJumpMsg = idx; }
        }
        if (it.hasExtra("jumpDay")) {
            String iso = it.getStringExtra("jumpDay");
            it.removeExtra("jumpDay");
            if (iso != null) { if (dataReady) applyJumpDay(iso); else pendingJumpDay = iso; }
        }
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleJumpIntent(it);
    }

    void applyJumpMsg(int idx) {
        if (adapter == null || idx < 0 || idx >= msgs.size()) return;
        highlightMsg = idx;
        adapter.notifyDataSetChanged();
        listView.setSelection(msgRow[idx]);
        scheduleClearHighlight();
    }

    void applyJumpDay(String iso) {
        if (adapter == null || dayIso.isEmpty()) return;
        int pick = dayIso.size() - 1;
        for (int i = 0; i < dayIso.size(); i++) {
            if (dayIso.get(i).compareTo(iso) >= 0) { pick = i; break; }
        }
        int row = dayFirstRow.get(pick);
        highlightRow = row;
        adapter.notifyDataSetChanged();
        listView.setSelection(row);
        scheduleClearHighlight();
    }

    // Star/delete state can change in the gallery; stay in sync.
    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        Set<String> del = prefs.getStringSet("deleted", new HashSet<String>());
        if (!del.equals(deleted)) {
            deleted = new HashSet<>(del);
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        Set<String> st = prefs.getStringSet("starred", new HashSet<String>());
        if (!st.equals(starred)) starred = new HashSet<>(st);
    }

    // ---------------- message dialog / menu / search / date ----------------
    void showMsgDialog(final int msgIndex) {
        Msg m = msgs.get(msgIndex);
        boolean isDeleted = deleted.contains(String.valueOf(msgIndex));
        List<String> items = new ArrayList<>();
        final List<Integer> acts = new ArrayList<>();
        if (!isDeleted && !m.waDeleted && m.text != null && m.text.length() > 0) { items.add("Copy"); acts.add(0); }
        if (!isDeleted) { items.add("Delete"); acts.add(1); }
        else { items.add("Restore"); acts.add(2); }
        if (!isDeleted) {
            items.add(starred.contains(String.valueOf(msgIndex)) ? "Unstar" : "Star");
            acts.add(3);
        }
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    int act = acts.get(w);
                    if (act == 0) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("message", msgs.get(msgIndex).text));
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                    } else if (act == 1) {
                        deleted.add(String.valueOf(msgIndex));
                        saveDeleted();
                        if (adapter != null) adapter.notifyDataSetChanged();
                    } else if (act == 3) {
                        String k = String.valueOf(msgIndex);
                        if (starred.contains(k)) starred.remove(k); else starred.add(k);
                        saveStarred();
                        if (adapter != null) adapter.notifyDataSetChanged();
                    } else {
                        deleted.remove(String.valueOf(msgIndex));
                        saveDeleted();
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                }).show();
    }

    void saveDeleted() {
        prefs.edit().putStringSet("deleted", new HashSet<>(deleted)).apply();
    }

    void saveStarred() {
        prefs.edit().putStringSet("starred", new HashSet<>(starred)).apply();
    }

    void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 11, 0, "Starred messages");
        pm.getMenu().add(0, 12, 0, "Media gallery");
        pm.getMenu().add(0, 13, 0, light ? "Dark theme" : "Light theme");
        pm.getMenu().add(0, 14, 0, "Bubble colour");
        pm.getMenu().add(0, 15, 0, "Add media files");
        pm.getMenu().add(0, 16, 0, "Import another chat");
        pm.getMenu().add(0, 18, 0, "Chat info");
        pm.getMenu().add(0, 17, 0, "Delete this chat");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 11: showStarred(); return true;
                case 12: {
                    Intent gi = new Intent(ChatActivity.this, GalleryActivity.class);
                    gi.putExtra("chatDir", chatDir.getAbsolutePath());
                    startActivity(gi);
                    return true;
                }
                case 13: setTheme(light ? "dark" : "light"); return true;
                case 14: showBubblePicker(); return true;
                case 15: addMediaFiles(); return true;
                case 16:
                    startActivity(new Intent(ChatActivity.this, ImportActivity.class));
                    return true;
                case 18: showStats(); return true;
                case 17:
                    new AlertDialog.Builder(this)
                            .setMessage("Ye chat app se delete kar dun?")
                            .setPositiveButton("Delete", (d, w) -> {
                                deleteRecursive(chatDir);
                                Intent hi = new Intent(ChatActivity.this, ImportActivity.class);
                                hi.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(hi);
                                finish();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
            }
            return false;
        });
        pm.show();
    }

    void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    void setTheme(String t) {
        if (t.equals(theme)) return;
        theme = t;
        prefs.edit().putString("theme", t).apply();
        recreate();
    }

    void showBubblePicker() {
        final String[] names = {"Default", "Red", "Blue", "Purple", "Teal", "Orange", "Pink"};
        final String[] keys = {"default", "red", "blue", "purple", "teal", "orange", "pink"};
        int cur = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(bubbleKey)) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("Bubble colour")
                .setSingleChoiceItems(names, cur, (d, w) -> {
                    bubbleKey = keys[w];
                    prefs.edit().putString("bubble", bubbleKey).apply();
                    d.dismiss();
                    recreate();
                }).show();
    }

    void showStats() {
        try {
            String s = meta.getInt("withSender") + " messages \u2022 " + meta.getInt("vn") + " voice notes \u2022 "
                    + meta.getInt("days") + " days\n" + meta.getString("firstLabel") + " \u2192 " + meta.getString("lastLabel");
            new AlertDialog.Builder(this).setTitle(chatTitle).setMessage(s).setPositiveButton("OK", null).show();
        } catch (Exception ignored) { }
    }

    void showStarred() {
        final List<Integer> idxs = new ArrayList<>();
        for (String s : starred) { try { idxs.add(Integer.parseInt(s)); } catch (Exception ignored) { } }
        idxs.removeIf(i -> i < 0 || i >= msgs.size() || deleted.contains(String.valueOf(i)));
        Collections.sort(idxs);
        if (idxs.isEmpty()) {
            Toast.makeText(this, "No starred messages yet \u2014 long-press a message and tap Star", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[idxs.size()];
        for (int i = 0; i < idxs.size(); i++) {
            Msg m = msgs.get(idxs.get(i));
            String snip;
            if (m.kind == K_AUDIO) snip = "\uD83C\uDFA4 Voice note (" + fmtDur(m.dur) + ")";
            else if (m.kind == K_PHOTO) snip = "\uD83D\uDCF7 Photo" + (m.text != null && m.text.length() > 0 ? " \u2014 " + m.text : "");
            else if (m.kind == K_VIDEO) snip = "\uD83C\uDFAC Video";
            else if (m.kind == K_STICKER) snip = "\uD83C\uDF9E Sticker";
            else if (m.kind == K_CALL) snip = "\uD83D\uDCDE Call";
            else if (m.kind == K_DOC || m.kind == K_DOCMISS) snip = "\uD83D\uDCC4 " + (m.file != null ? m.file : "Document");
            else snip = m.text == null ? "" : m.text.replace("\n", " ");
            if (snip.length() > 70) snip = snip.substring(0, 70) + "\u2026";
            labels[i] = m.dateLabel + " \u2022 " + m.time + "\n" + snip;
        }
        new AlertDialog.Builder(this)
                .setTitle("Starred messages (" + idxs.size() + ")")
                .setItems(labels, (d, w) -> {
                    int idx = idxs.get(w);
                    highlightMsg = idx;
                    if (adapter != null) adapter.notifyDataSetChanged();
                    listView.setSelection(msgRow[idx]);
                    scheduleClearHighlight();
                }).show();
    }

    // search
    List<Integer> matches = new ArrayList<>();
    String lastQuery = null;
    int matchPos = 0;
    Runnable clearHlRun;

    void toggleSearch() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            searchBar.setVisibility(View.GONE);
            clearHighlight();
            hideKeyboard();
        } else {
            searchBar.setVisibility(View.VISIBLE);
            searchBox.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    void runSearch() {
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.length() == 0) return;
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            matches.clear();
            for (int i = 0; i < msgs.size(); i++) {
                if (msgs.get(i).sender != -1 && !deleted.contains(String.valueOf(i)) && lowerTexts[i].contains(q)) matches.add(i);
            }
            matchPos = 0;
        } else if (!matches.isEmpty()) {
            matchPos = (matchPos + 1) % matches.size();
        }
        if (matches.isEmpty()) {
            searchInfo.setText("No message found");
            return;
        }
        int idx = matches.get(matchPos);
        searchInfo.setText(matches.size() + " found \u2022 " + (matchPos + 1) + "/" + matches.size() + " \u2022 Enter = next");
        highlightMsg = idx;
        if (adapter != null) adapter.notifyDataSetChanged();
        listView.setSelection(msgRow[idx]);
        scheduleClearHighlight();
    }

    void scheduleClearHighlight() {
        if (clearHlRun != null) ui.removeCallbacks(clearHlRun);
        clearHlRun = () -> { highlightMsg = -1; highlightRow = -1; if (adapter != null) adapter.notifyDataSetChanged(); };
        ui.postDelayed(clearHlRun, 1800);
    }

    void clearHighlight() {
        highlightMsg = -1;
        highlightRow = -1;
        lastQuery = null;
        matches.clear();
        searchInfo.setText("");
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    void showDatePicker() {
        if (meta == null) return;
        try {
            String[] f = meta.getString("firstIso").split("-");
            String[] l = meta.getString("lastIso").split("-");
            Calendar cMin = Calendar.getInstance();
            cMin.set(Integer.parseInt(f[0]), Integer.parseInt(f[1]) - 1, Integer.parseInt(f[2]), 0, 0, 0);
            Calendar cMax = Calendar.getInstance();
            cMax.set(Integer.parseInt(l[0]), Integer.parseInt(l[1]) - 1, Integer.parseInt(l[2]), 23, 59, 59);
            DatePickerDialog dlg = new DatePickerDialog(this, (view, y, mo, d) -> {
                String iso = String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, d);
                int pick = dayIso.size() - 1;
                for (int i = 0; i < dayIso.size(); i++) {
                    if (dayIso.get(i).compareTo(iso) >= 0) { pick = i; break; }
                }
                int row = dayFirstRow.get(pick);
                highlightRow = row;
                if (adapter != null) adapter.notifyDataSetChanged();
                listView.setSelection(row);
                scheduleClearHighlight();
            }, Integer.parseInt(l[0]), Integer.parseInt(l[1]) - 1, Integer.parseInt(l[2]));
            dlg.getDatePicker().setMinDate(cMin.getTimeInMillis());
            dlg.getDatePicker().setMaxDate(cMax.getTimeInMillis());
            dlg.show();
        } catch (Exception ignored) { }
    }
}
