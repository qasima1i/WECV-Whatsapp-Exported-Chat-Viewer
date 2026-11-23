package com.wecv.viewer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Photo viewer (v9): pinch-zoom as before, plus swipe to the next/previous
// photo, and a top bar with Go to message / Save / Share. The photo list is
// read from the bundled chat data, so swiping walks the chat's photos in
// order and every photo knows its message.
public class PhotoActivity extends Activity {
    static class Photo {
        String file, label;
        int idx;
    }

    private ImageView iv;
    private LinearLayout topBar;
    private TextView titleTv;
    private final Matrix matrix = new Matrix();
    private ScaleGestureDetector sgd;
    private GestureDetector gd;
    private float lastX, lastY;
    private boolean down;
    private float fitScale = 1f;

    private File chatDir;
    private final List<Photo> photos = new ArrayList<>();
    private int pos = -1;
    private String pendingAction;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xB3000000);
        topBar.setPadding(dp(4), dp(6), dp(8), dp(6));
        TextView back = barText("\u2190", 22);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        titleTv = barText("", 13.5f);
        topBar.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));
        TextView goBtn = barBtn("Chat mein jao");
        goBtn.setOnClickListener(v -> {
            if (pos >= 0) startActivity(ChatUtil.jumpMsgIntent(PhotoActivity.this, photos.get(pos).idx));
        });
        topBar.addView(goBtn);
        TextView saveBtn = barBtn("Save");
        saveBtn.setOnClickListener(v -> saveOrShare(false));
        topBar.addView(saveBtn);
        TextView shareBtn = barBtn("Share");
        shareBtn.setOnClickListener(v -> saveOrShare(true));
        topBar.addView(shareBtn);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2);
        tlp.gravity = Gravity.TOP;
        root.addView(topBar, tlp);
        setContentView(root);

        String cd = getIntent().getStringExtra("chatDir");
        chatDir = cd != null ? new File(cd) : getFilesDir();
        final String startFile = getIntent().getStringExtra("file");
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
                    if (!o.getString("k").equals("photo")) continue;
                    String f = o.optString("f", "");
                    if (f.length() == 0) continue;
                    Photo p = new Photo();
                    p.file = f;
                    p.idx = i;
                    p.label = o.getString("d") + " \u2022 " + o.getString("t");
                    if (f.equals(startFile)) pos = photos.size();
                    photos.add(p);
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (pos < 0 && !photos.isEmpty()) pos = 0;
                if (pos >= 0) load(pos);
                else if (startFile != null) { // fallback: show what we were given
                    Photo p = new Photo();
                    p.file = startFile;
                    p.idx = getIntent().getIntExtra("msgIndex", -1);
                    p.label = getIntent().getStringExtra("label");
                    photos.add(p);
                    pos = 0;
                    load(0);
                }
            });
        }).start();

        sgd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                matrix.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                iv.setImageMatrix(matrix);
                return true;
            }
        });
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                topBar.setVisibility(topBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                return true;
            }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                Bitmap bm = (Bitmap) iv.getTag();
                if (bm != null) fit(bm);
                return true;
            }
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || pos < 0) return false;
                float[] vals = new float[9];
                matrix.getValues(vals);
                if (vals[Matrix.MSCALE_X] > fitScale * 1.08f) return false; // zoomed in: pan, don't swipe
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > dp(56) && Math.abs(vx) > Math.abs(vy) * 1.4f) {
                    if (dx < 0 && pos < photos.size() - 1) load(pos + 1);
                    else if (dx > 0 && pos > 0) load(pos - 1);
                    return true;
                }
                return false;
            }
        });
        iv.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY(); down = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (down && e.getPointerCount() == 1 && !sgd.isInProgress()) {
                        matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        iv.setImageMatrix(matrix);
                        lastX = e.getX(); lastY = e.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    break;
            }
            return true;
        });
    }

    private TextView barText(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        return t;
    }

    private TextView barBtn(String s) {
        TextView t = barText(s, 13.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(7), dp(11), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void load(int p) {
        pos = p;
        final Photo ph = photos.get(p);
        titleTv.setText(ph.label != null ? ph.label : "");
        new Thread(() -> {
            try {
                InputStream is = new java.io.FileInputStream(new File(new File(chatDir, "media"), ph.file));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, o);
                is.close();
                int maxDim = Math.max(o.outWidth, o.outHeight);
                int sample = 1;
                while (maxDim / sample > 2048) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                InputStream is2 = new java.io.FileInputStream(new File(new File(chatDir, "media"), ph.file));
                final Bitmap bm = BitmapFactory.decodeStream(is2, null, o2);
                is2.close();
                runOnUiThread(() -> {
                    if (photos.indexOf(ph) != pos) return;
                    iv.setImageBitmap(bm);
                    iv.post(() -> fit(bm));
                });
            } catch (Exception ignored) { }
        }).start();
    }

    private void fit(Bitmap bm) {
        if (bm == null) return;
        iv.setTag(bm);
        float sw = iv.getWidth(), sh = iv.getHeight();
        if (sw == 0 || sh == 0) return;
        float sc = Math.min(sw / bm.getWidth(), sh / bm.getHeight());
        fitScale = sc;
        matrix.reset();
        matrix.postScale(sc, sc);
        matrix.postTranslate((sw - bm.getWidth() * sc) / 2f, (sh - bm.getHeight() * sc) / 2f);
        iv.setImageMatrix(matrix);
    }

    private void saveOrShare(boolean share) {
        if (pos < 0) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = share ? "share" : "save";
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 90);
            return;
        }
        File f = new File(new File(chatDir, "media"), photos.get(pos).file);
        if (share) ChatUtil.shareWithChooser(this, f, false);
        else ChatUtil.saveWithToast(this, f, false);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grantResults) {
        super.onRequestPermissionsResult(req, perms, grantResults);
        if (req == 90 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingAction != null) {
            saveOrShare("share".equals(pendingAction));
        } else if (req == 90) {
            Toast.makeText(this, "Permission ke baghair save nahi ho sakta", Toast.LENGTH_SHORT).show();
        }
        pendingAction = null;
    }
}
