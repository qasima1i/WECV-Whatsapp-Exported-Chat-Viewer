package com.wecv.viewer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

// Video viewer (v9): the same player as before, plus a top bar with
// Go to message / Save / Share, matching the photo viewer.
public class VideoActivity extends Activity {
    private String file;
    private int msgIndex = -1;
    private String pendingAction;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        final VideoView vv = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1);
        vlp.gravity = Gravity.CENTER;
        root.addView(vv, vlp);
        final ProgressBar pb = new ProgressBar(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.CENTER;
        root.addView(pb, plp);

        file = getIntent().getStringExtra("file");
        msgIndex = getIntent().getIntExtra("msgIndex", -1);
        String label = getIntent().getStringExtra("label");

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xB3000000);
        topBar.setPadding(dp(4), dp(6), dp(8), dp(6));
        TextView back = barText("\u2190", 22);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView titleTv = barText(label != null ? label : "", 13.5f);
        topBar.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));
        TextView goBtn = barBtn("Chat mein jao");
        goBtn.setOnClickListener(v -> {
            if (msgIndex >= 0) startActivity(ChatUtil.jumpMsgIntent(VideoActivity.this, msgIndex));
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
        final File vfile = new File(new File(cd != null ? new File(cd) : getFilesDir(), "media"), file);
        runOnUiThread(() -> {
            pb.setVisibility(View.GONE);
            MediaController mc = new MediaController(VideoActivity.this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoPath(vfile.getAbsolutePath());
            vv.setOnErrorListener((mp, w, e) -> {
                Toast.makeText(VideoActivity.this, "Video play nahi ho saki", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            });
            vv.start();
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

    private void saveOrShare(boolean share) {
        if (file == null) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = share ? "share" : "save";
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 91);
            return;
        }
        String cd2 = getIntent().getStringExtra("chatDir");
        File f = new File(new File(cd2 != null ? new File(cd2) : getFilesDir(), "media"), file);
        if (share) ChatUtil.shareWithChooser(this, f, true);
        else ChatUtil.saveWithToast(this, f, true);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grantResults) {
        super.onRequestPermissionsResult(req, perms, grantResults);
        if (req == 91 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingAction != null) {
            saveOrShare("share".equals(pendingAction));
        } else if (req == 91) {
            Toast.makeText(this, "Permission ke baghair save nahi ho sakta", Toast.LENGTH_SHORT).show();
        }
        pendingAction = null;
    }
}
