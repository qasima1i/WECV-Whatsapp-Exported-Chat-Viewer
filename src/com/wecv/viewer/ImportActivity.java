package com.wecv.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// WECV home screen: the library of imported chats plus the import flow.
// Import accepts a WhatsApp export ZIP (chat + media together) or a plain
// _chat.txt (media can be added later from the chat's menu). After parsing,
// the user picks which sender is them so messages land on the right side.
public class ImportActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    LinearLayout libCol;
    float density;

    int dp(float v) { return (int) (v * density + 0.5f); }

    TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        buildHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (libCol != null) refreshLibrary();
    }

    void buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B141A);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF1F2C34);
        header.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView name = tv("WECV", 24, 0xFFE9EDEF);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(name);
        header.addView(tv("WhatsApp Exported Chat Viewer", 13.5f, 0xFF8696A0));
        root.addView(header);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(dp(16), dp(16), dp(16), dp(6));
        Button zipBtn = bigButton("📦  ZIP file se import karo");
        zipBtn.setOnClickListener(v -> pickFile(61, "application/zip"));
        btns.addView(zipBtn);
        Button txtBtn = bigButton("📄  Chat .txt se import karo");
        txtBtn.setOnClickListener(v -> pickFile(62, "text/plain"));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        btns.addView(txtBtn, tlp);
        TextView hint = tv("WhatsApp → chat kholo → ⋮ → More → Export chat → Include media (ZIP banegi)", 12.5f, 0xFF8696A0);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(10);
        btns.addView(hint, hlp);
        root.addView(btns);

        TextView libTitle = tv("MERI CHATS", 12, 0xFF00A884);
        libTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        libTitle.setPadding(dp(18), dp(14), 0, dp(4));
        root.addView(libTitle);

        ScrollView scroll = new ScrollView(this);
        libCol = new LinearLayout(this);
        libCol.setOrientation(LinearLayout.VERTICAL);
        libCol.setPadding(dp(16), dp(4), dp(16), dp(20));
        scroll.addView(libCol);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refreshLibrary();
    }

    Button bigButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15.5f);
        b.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF00A884);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setPadding(0, dp(6), 0, dp(6));
        return b;
    }

    File chatsRoot() {
        File f = new File(getFilesDir(), "chats");
        f.mkdirs();
        return f;
    }

    void refreshLibrary() {
        libCol.removeAllViews();
        File[] dirs = chatsRoot().listFiles();
        List<File> chats = new ArrayList<>();
        if (dirs != null) for (File d : dirs) if (new File(d, "meta.json").exists()) chats.add(d);
        chats.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (chats.isEmpty()) {
            libCol.addView(tv("Abhi koi chat import nahi hui. Upar se pehli chat import karo.", 14, 0xFF8696A0));
            return;
        }
        for (final File d : chats) {
            try {
                JSONObject meta = new JSONObject(Prep.readText(new File(d, "meta.json")));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(13), dp(16), dp(13));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFF1F2C34);
                bg.setCornerRadius(dp(14));
                row.setBackground(bg);
                row.addView(tv(meta.getString("title"), 16.5f, 0xFFE9EDEF));
                TextView sub = tv(meta.optString("firstLabel") + " – " + meta.optString("lastLabel") + " • " + meta.optInt("count") + " messages", 12.5f, 0xFF8696A0);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
                slp.topMargin = dp(3);
                row.addView(sub, slp);
                row.setOnClickListener(v -> openChat(d.getName()));
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setMessage("Ye chat app se hata dun? (Phone ki asal export file wesi hi rahegi)")
                            .setPositiveButton("Delete", (dg, w) -> { deleteRecursive(d); refreshLibrary(); })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(10);
                libCol.addView(row, lp);
            } catch (Exception ignored) { }
        }
    }

    void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    void openChat(String chatId) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("chatId", chatId);
        startActivity(i);
    }

    // ---------------- import flow ----------------
    void pickFile(int req, String type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == 61) startImport(data.getData(), true);
        else if (req == 62) startImport(data.getData(), false);
    }

    void showProgress(String label) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0B141A);
        root.setPadding(dp(30), dp(30), dp(30), dp(30));
        ProgressBar pb = new ProgressBar(this);
        root.addView(pb);
        TextView t = tv(label, 15, 0xFFE9EDEF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        root.addView(t, lp);
        root.setTag(t);
        setContentView(root);
    }

    void setProgressLabel(String s) {
        View root = findViewById(android.R.id.content);
        if (root instanceof LinearLayout) {
            Object tag = ((LinearLayout) root).getTag();
            if (tag instanceof TextView) ((TextView) tag).setText(s);
        }
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String safeName(String name) {
        String n = name.replace("\\", "/");
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        return n.replace("..", "").trim();
    }

    static String titleFromFileName(String name) {
        if (name == null) return null;
        String n = name;
        if (n.toLowerCase().endsWith(".txt")) n = n.substring(0, n.length() - 4);
        if (n.startsWith("WhatsApp Chat with ")) n = n.substring("WhatsApp Chat with ".length());
        if (n.equals("_chat")) return null;
        return n.trim();
    }

    void startImport(final Uri uri, final boolean isZip) {
        showProgress("File parh raha hun…");
        new Thread(() -> {
            try {
                final File work = new File(getCacheDir(), "import_" + System.currentTimeMillis());
                final File mediaDir = new File(work, "media");
                mediaDir.mkdirs();
                final List<String> lines = new ArrayList<>();
                String titleHint = null;
                if (isZip) {
                    ZipInputStream zis = new ZipInputStream(getContentResolver().openInputStream(uri));
                    ZipEntry e;
                    List<String> txtLines = null;
                    String txtName = null;
                    byte[] buf = new byte[65536];
                    while ((e = zis.getNextEntry()) != null) {
                        String name = safeName(e.getName());
                        if (name.length() == 0 || e.isDirectory()) continue;
                        if (name.toLowerCase().endsWith(".txt")) {
                            List<String> ls = readLines(new java.io.ByteArrayInputStream(readEntryBytes(zis)));
                            if (txtLines == null || name.equals("_chat.txt") || name.startsWith("WhatsApp Chat")) {
                                txtLines = ls;
                                txtName = name;
                            }
                        } else {
                            File out = new File(mediaDir, name);
                            FileOutputStream fos = new FileOutputStream(out);
                            int n;
                            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                            fos.close();
                        }
                    }
                    zis.close();
                    if (txtLines == null) throw new Exception("ZIP mein chat .txt nahi mili");
                    lines.addAll(txtLines);
                    titleHint = titleFromFileName(txtName);
                } else {
                    titleHint = titleFromFileName(displayName(uri));
                    lines.addAll(readLines(getContentResolver().openInputStream(uri)));
                }
                Set<String> mediaSet = new HashSet<>();
                File[] fs = mediaDir.listFiles();
                if (fs != null) for (File f : fs) mediaSet.add(f.getName());
                final ChatParser.Result parsed = ChatParser.parse(lines, mediaSet);
                if (parsed.senders.isEmpty()) throw new Exception("Is file mein koi chat nahi mili — sahi export file chunein");
                final String th = titleHint;
                ui.post(() -> showSenderPick(work, parsed, th));
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                ui.post(() -> {
                    buildHome();
                    Toast.makeText(this, "Import nahi ho saka: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // Reads one zip entry to its end without closing the ZipInputStream.
    byte[] readEntryBytes(ZipInputStream zis) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    List<String> readLines(InputStream is) throws Exception {
        List<String> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) out.add(line);
        br.close();
        return out;
    }

    void showSenderPick(final File work, final ChatParser.Result parsed, final String titleHint) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B141A);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        TextView q = tv("Is chat mein aap kon hain?", 20, 0xFFE9EDEF);
        q.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(q);
        root.addView(tv("Aapke messages right side par ayenge, doosre ke left par.", 13.5f, 0xFF8696A0));
        ListView list = new ListView(this);
        list.setDivider(null);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1);
        llp.topMargin = dp(16);
        root.addView(list, llp);
        setContentView(root);

        final List<String> names = new ArrayList<>(parsed.senders.keySet());
        final List<Map<String, String>> rowsData = new ArrayList<>();
        for (String n : names) {
            Map<String, String> m = new HashMap<>();
            m.put("t", n);
            m.put("s", parsed.senders.get(n) + " messages");
            rowsData.add(m);
        }
        Map<String, String> none = new HashMap<>();
        none.put("t", "Main in mein se koi nahi");
        none.put("s", "Sab messages left side, naam ke sath (group view)");
        rowsData.add(none);
        SimpleAdapter ad = new SimpleAdapter(this, rowsData, android.R.layout.simple_list_item_2,
                new String[]{"t", "s"}, new int[]{android.R.id.text1, android.R.id.text2});
        list.setAdapter(ad);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            String me = pos < names.size() ? names.get(pos) : null;
            finishImport(work, parsed, titleHint, me);
        });
    }

    void finishImport(final File work, final ChatParser.Result parsed, final String titleHint, final String me) {
        showProgress("Chat tayyar ho rahi hai…");
        new Thread(() -> {
            try {
                String chatId = "c" + System.currentTimeMillis();
                File chatDir = new File(chatsRoot(), chatId);
                File mediaDest = new File(chatDir, "media");
                mediaDest.mkdirs();
                File[] fs = new File(work, "media").listFiles();
                if (fs != null) for (File f : fs) {
                    File dest = new File(mediaDest, f.getName());
                    InputStream is = new java.io.FileInputStream(f);
                    OutputStream os = new FileOutputStream(dest);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    is.close();
                }
                Prep.build(this, chatDir, parsed, me, titleHint,
                        (what, done, total) -> ui.post(() -> setProgressLabel(what + " tayyar ho raha hai… " + done + "/" + total)));
                deleteRecursive(work);
                ui.post(() -> {
                    buildHome();
                    openChat(chatId);
                });
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                ui.post(() -> {
                    buildHome();
                    Toast.makeText(this, "Import nahi ho saka: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
