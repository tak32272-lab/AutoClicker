package com.example.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ユーザー補助サービス本体。
 * ・画面の上に⏯️パネルを出す
 * ・記録：透明な膜で画面全体のタッチを拾い、座標を記録してから下のアプリへ同じ操作を流す
 * ・再生：記録した操作を dispatchGesture で再現する
 * ・画像探索：スクリーンショットから登録画像を探し、見つけたらタップする（Android 11 以上）
 */
public class ClickerService extends AccessibilityService {

    static ClickerService instance;

    private static final int IDLE = 0, REC = 1, READY = 2, PLAY = 3, SCAN = 4, BUSY = 5;
    private int state = IDLE;

    /** 色の違いがこれより大きければ「別物」とする */
    private static final float COLOR_TOL = 60f;
    /** 大きさの変化に対応するときに試す倍率 */
    private static final float[] SCALES = {0.8f, 1f, 1.25f};
    /** スクリーンショットの間隔（Android の制限で約0.33秒に1回まで） */
    private static final long SHOT_INTERVAL = 360;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService shotExec = Executors.newSingleThreadExecutor();

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView mainBtn, titleTv, subTv, scanBtn;
    private volatile int[] panelRect = {0, 0, 0, 0};

    private View recLayer;
    private WindowManager.LayoutParams recLp;
    private boolean passing = false;

    private FrameLayout cropRoot;
    private int[] shotBuf;

    private final List<Action> actions = new ArrayList<>();
    private long recStart, recDuration;
    private ArrayList<float[]> curPts;
    private long curDown;

    private volatile boolean abort = false;

    static class Action {
        long t;     // 記録開始からの時間(ms)
        long dur;   // 押していた時間(ms)
        float[] xs, ys;
    }

    // =====================================================================
    // サービスの開始・終了
    // =====================================================================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        load();
        state = actions.isEmpty() ? IDLE : READY;
        showPanel();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { abort = true; }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        abort = true;
        removeRecLayer();
        removeCrop();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
        shotExec.shutdownNow();
        instance = null;
    }

    // =====================================================================
    // 共通
    // =====================================================================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ac", MODE_PRIVATE);
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static WindowManager.LayoutParams baseLp(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private static final int FULL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    /** 文字の大きさは dp 指定（スマホの文字サイズ設定でパネルが崩れないように） */
    private TextView label(String text, float sizeDp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView smallBtn(String text, View.OnClickListener l) {
        TextView t = label(text, 12, Color.WHITE);
        t.setMaxLines(1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A3340);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams weightLp(int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(hDp), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private static int workFactor(int screenW) {
        return Math.max(1, Math.round(screenW / 360f));
    }

    // =====================================================================
    // ⏯️パネル
    // =====================================================================

    void showPanel() {
        if (wm == null) return;
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
            render();
            return;
        }

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C222B);
        bg.setCornerRadius(dp(18));
        panel.setBackground(bg);

        // 1段目：⏯️ / 状態 / ✕
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        mainBtn = label("⏯️", 24, Color.WHITE);
        mainBtn.setOnClickListener(v -> onMain());
        mainBtn.setOnLongClickListener(v -> {           // 長押しで記録し直し
            if (state == IDLE || state == READY) startRec();
            return true;
        });
        row1.addView(mainBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, dp(4), 0);
        titleTv = label("", 14, Color.WHITE);
        titleTv.setGravity(Gravity.START);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        subTv = label("", 11, 0xFFB0BAC6);
        subTv.setGravity(Gravity.START);
        subTv.setMaxLines(2);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleTv);
        texts.addView(subTv);
        row1.addView(texts, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 文字の部分をドラッグするとパネルが動く
        texts.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = panelLp.x;
                        oy = panelLp.y;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = ox + (int) (e.getRawX() - sx);
                        panelLp.y = oy + (int) (e.getRawY() - sy);
                        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
                        break;
                    case MotionEvent.ACTION_UP:
                        ui.postDelayed(ClickerService.this::updatePanelRect, 100);
                        break;
                }
                return true;
            }
        });

        TextView closeBtn = label("✕", 20, Color.WHITE);
        closeBtn.setOnClickListener(v -> hidePanel());
        row1.addView(closeBtn, new LinearLayout.LayoutParams(dp(36), dp(52)));
        panel.addView(row1);

        // 2段目：🔍探す / 📷登録 / 🔲範囲 / 🗑消去
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(4), 0, 0);
        scanBtn = smallBtn("🔍 探す", v -> onScanBtn());
        row2.addView(scanBtn, weightLp(34));
        row2.addView(smallBtn("📷 登録", v -> startCapture(false)), weightLp(34));
        row2.addView(smallBtn("🔲 範囲", v -> startCapture(true)), weightLp(34));
        row2.addView(smallBtn("🗑 消去", v -> clearAll()), weightLp(34));
        panel.addView(row2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updatePanelRect());

        panelLp = baseLp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(12);
        panelLp.y = dp(120);
        wm.addView(panel, panelLp);
        render();
    }

    /** パネルの画面上の位置（探索のとき、ここはタップしない） */
    private void updatePanelRect() {
        if (panel == null || panel.getWidth() == 0) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        panelRect = new int[]{loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight()};
    }

    private void hidePanel() {
        abort = true;
        if (state == REC) stopRec();
        removeCrop();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
        Toast.makeText(this, "パネルはアプリの「パネルを表示」で戻せます", Toast.LENGTH_SHORT).show();
    }

    void render() {
        if (panel == null) return;
        SharedPreferences sp = prefs();
        // 透明度 0〜80%（完全に消えると押せなくなるので最大80%）
        panel.setAlpha(1f - Math.max(0, Math.min(80, sp.getInt("trans", 50))) / 100f);
        String cfg = "×" + sp.getInt("count", 100) + "回・速度" + sp.getInt("speed", 10);
        int color;
        String t, s;
        switch (state) {
            case REC:   color = 0xFFFF4A5A; t = "● 記録中 " + actions.size(); s = "⏯️で記録終了"; break;
            case READY: color = 0xFF2F9CFF; t = "記録 " + actions.size() + "タップ"; s = "⏯️で再生 " + cfg; break;
            case PLAY:  color = 0xFF2FD17A; t = "▶ 再生中"; s = "⏯️で中断"; break;
            case SCAN:  color = 0xFF9B59F6; t = "🔍 探索中"; s = "⏯️か⏹で停止"; break;
            case BUSY:  color = 0xFF3A4553; t = "範囲を選択中"; s = ""; break;
            default:    color = 0xFF3A4553; t = "待機中"; s = "⏯️で記録開始"; break;
        }
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(color);
        mainBtn.setBackground(c);
        scanBtn.setText(state == SCAN ? "⏹ 停止" : "🔍 探す");
        setText(t, s);
    }

    private void setText(String t, String s) {
        if (titleTv == null) return;
        titleTv.setText(t);
        subTv.setText(s);
    }

    private void onMain() {
        switch (state) {
            case IDLE:  startRec(); break;
            case REC:   stopRec(); break;
            case READY: startPlay(); break;
            case PLAY:
            case SCAN:  abort = true; break;
            default:    break;
        }
    }

    /** 記録したタップを消す */
    private void clearAll() {
        if (state == SCAN || state == BUSY) {
            toast("探索を止めてから押してください");
            return;
        }
        abort = true;
        if (state == REC) removeRecLayer();
        actions.clear();
        recDuration = 0;
        state = IDLE;
        save();
        render();
    }

    // =====================================================================
    // 記録
    // =====================================================================

    private void startRec() {
        actions.clear();
        passing = false;
        curPts = null;

        recLayer = new View(this);
        recLayer.setBackgroundColor(0x18FF3040);   // 記録中だとわかるよう薄く赤くする
        recLp = baseLp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, FULL_FLAGS);
        recLayer.setOnTouchListener((v, e) -> {
            onRecTouch(e);
            return true;
        });
        wm.addView(recLayer, recLp);

        // パネルを膜より上に出し直す
        try { wm.removeView(panel); } catch (Exception ignored) { }
        wm.addView(panel, panelLp);

        recStart = SystemClock.uptimeMillis();
        state = REC;
        render();
    }

    private void onRecTouch(MotionEvent e) {
        if (passing) return;
        float x = Math.max(0, e.getRawX());
        float y = Math.max(0, e.getRawY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                curPts = new ArrayList<>();
                curPts.add(new float[]{x, y});
                curDown = e.getEventTime();
                break;
            case MotionEvent.ACTION_MOVE:
                if (curPts == null) break;
                float[] last = curPts.get(curPts.size() - 1);
                if (Math.hypot(x - last[0], y - last[1]) > dp(6)) curPts.add(new float[]{x, y});
                break;
            case MotionEvent.ACTION_UP: {
                if (curPts == null) break;
                float[] first = curPts.get(0);
                float[] lp = curPts.get(curPts.size() - 1);
                if (Math.hypot(x - lp[0], y - lp[1]) > 1) curPts.add(new float[]{x, y});
                if (Math.hypot(x - first[0], y - first[1]) < dp(12) && curPts.size() <= 3) {
                    // ほとんど動いていなければタップとして扱う
                    curPts.subList(1, curPts.size()).clear();
                }
                Action a = new Action();
                a.t = Math.max(0, curDown - recStart);
                a.dur = Math.max(1, e.getEventTime() - curDown);
                a.xs = new float[curPts.size()];
                a.ys = new float[curPts.size()];
                for (int i = 0; i < curPts.size(); i++) {
                    a.xs[i] = curPts.get(i)[0];
                    a.ys[i] = curPts.get(i)[1];
                }
                curPts = null;
                actions.add(a);
                render();
                passThrough(a);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                curPts = null;
                break;
        }
    }

    /** 記録したタップを、膜を一瞬すり抜けさせて下のアプリにも届ける */
    private void passThrough(Action a) {
        passing = true;
        recLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recLayer, recLp); } catch (Exception ignored) { }
        ui.postDelayed(() -> {
            boolean ok = dispatchGesture(build(a, 1f), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { endPass(); }
                @Override public void onCancelled(GestureDescription g) { endPass(); }
            }, ui);
            if (!ok) endPass();
        }, 60);
    }

    private void endPass() {
        ui.postDelayed(() -> {
            passing = false;
            if (recLayer != null && state == REC) {
                recLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                try { wm.updateViewLayout(recLayer, recLp); } catch (Exception ignored) { }
            }
        }, 30);
    }

    private void stopRec() {
        recDuration = SystemClock.uptimeMillis() - recStart;
        removeRecLayer();
        state = actions.isEmpty() ? IDLE : READY;
        save();
        render();
    }

    private void removeRecLayer() {
        if (recLayer != null) {
            try { wm.removeView(recLayer); } catch (Exception ignored) { }
            recLayer = null;
        }
    }

    // =====================================================================
    // 再生
    // =====================================================================

    private GestureDescription build(Action a, float mult) {
        Path p = new Path();
        p.moveTo(a.xs[0], a.ys[0]);
        for (int i = 1; i < a.xs.length; i++) p.lineTo(a.xs[i], a.ys[i]);
        long d = Math.round(a.dur / mult);
        d = Math.max(10, Math.min(GestureDescription.getMaxGestureDuration(), d));
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, d))
                .build();
    }

    private void startPlay() {
        if (actions.isEmpty()) return;
        SharedPreferences sp = prefs();
        final int n = Math.max(1, sp.getInt("count", 100));
        final float mult = Math.max(1, Math.min(100, sp.getInt("speed", 10))) / 10f;  // 10で等速
        final List<Action> list = new ArrayList<>(actions);
        final long total = recDuration;

        abort = false;
        state = PLAY;
        render();

        new Thread(() -> {
            boolean done = true;
            outer:
            for (int k = 1; k <= n; k++) {
                final int kk = k;
                ui.post(() -> setText("▶ 再生中 " + kk + "/" + n, "⏯️で中断"));
                long prevEnd = 0;
                for (Action a : list) {
                    if (!sleepAbortable((a.t - prevEnd) / mult)) { done = false; break outer; }
                    dispatchSync(build(a, mult));
                    prevEnd = a.t + a.dur;
                }
                if (k < n && !sleepAbortable((total - prevEnd) / mult)) { done = false; break; }
            }
            final boolean fin = done && !abort;
            ui.post(() -> {
                state = actions.isEmpty() ? IDLE : READY;
                render();
                if (state == READY) setText(fin ? "再生完了" : "中断しました", "⏯️でもう一度再生");
            });
        }).start();
    }

    private boolean sleepAbortable(float ms) {
        long end = SystemClock.uptimeMillis() + Math.max(0, (long) ms);
        while (!abort) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return true;
            try { Thread.sleep(Math.min(left, 20)); } catch (InterruptedException ex) { return false; }
        }
        return false;
    }

    /** ジェスチャーを送り、終わるまで待つ（メインスレッド以外から呼ぶ） */
    private void dispatchSync(final GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(() -> {
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { latch.countDown(); }
                @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
            }, ui);
            if (!ok) latch.countDown();
        });
        try {
            latch.await(GestureDescription.getMaxGestureDuration() + 2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { }
    }

    private void tapSync(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchSync(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build());
    }

    // =====================================================================
    // スクリーンショット（Android 11 以上）
    // =====================================================================

    private static final class Shot {
        int w, h;
        int[] argb;
    }

    /** 画面を撮る（終わるまで待つ。メインスレッド以外から呼ぶ）。失敗なら null */
    @TargetApi(30)
    private Shot takeShotSync(final boolean reuse) {
        final Shot[] out = new Shot[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    try { out[0] = toShot(r, reuse); } catch (Throwable ignored) { }
                    latch.countDown();
                }

                @Override
                public void onFailure(int errorCode) {
                    latch.countDown();
                }
            });
        } catch (Throwable ex) {
            return null;
        }
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }

    @TargetApi(30)
    private Shot toShot(ScreenshotResult r, boolean reuse) {
        HardwareBuffer hb = r.getHardwareBuffer();
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
            if (hw == null) return null;
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            if (sw == null) return null;
            Shot s = new Shot();
            s.w = sw.getWidth();
            s.h = sw.getHeight();
            int n = s.w * s.h;
            if (reuse && shotBuf != null && shotBuf.length == n) {
                s.argb = shotBuf;
            } else {
                s.argb = new int[n];
                if (reuse) shotBuf = s.argb;
            }
            sw.getPixels(s.argb, 0, s.w, 0, 0, s.w, s.h);
            sw.recycle();
            return s;
        } finally {
            hb.close();
        }
    }

    // =====================================================================
    // 画像の登録・探す範囲の指定
    // =====================================================================

    private void startCapture(final boolean rangeMode) {
        if (Build.VERSION.SDK_INT < 30) {
            toast("画像の機能は Android 11 以上で使えます");
            return;
        }
        if (state != IDLE && state != READY) {
            toast("記録・再生・探索を止めてから押してください");
            return;
        }
        final int prev = state;
        state = BUSY;
        render();
        panel.setVisibility(View.INVISIBLE);       // パネルが写らないように隠す
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            final Shot shot = takeShotSync(false);
            ui.post(() -> {
                if (shot == null) {
                    closeCrop(prev);
                    toast("画面を撮れませんでした。ユーザー補助を一度オフ→オンにしてください");
                    return;
                }
                showCrop(shot, rangeMode, prev);
            });
        }).start();
    }

    private void showCrop(final Shot shot, final boolean rangeMode, final int prevState) {
        final Bitmap bmp = Bitmap.createBitmap(shot.argb, shot.w, shot.h, Bitmap.Config.ARGB_8888);
        cropRoot = new FrameLayout(this);
        final CropView cv = new CropView(this, bmp, dp(3));
        cropRoot.addView(cv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView guide = label(rangeMode
                        ? "探す範囲を、指でなぞって四角く囲んでください"
                        : "登録する画像（モンスターなど）を指でなぞって囲んでください\n周りに少し余白をつけるのがコツです",
                14, Color.WHITE);
        guide.setBackgroundColor(0xCC000000);
        guide.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        glp.topMargin = dp(40);
        cropRoot.addView(guide, glp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackgroundColor(0xCC000000);
        bar.addView(smallBtn("キャンセル", v -> closeCrop(prevState)), weightLp(44));
        if (rangeMode) {
            bar.addView(smallBtn("全画面にする", v -> {
                prefs().edit().putString("roi", "").apply();
                toast("探す範囲：全画面");
                closeCrop(prevState);
            }), weightLp(44));
        }
        bar.addView(smallBtn(rangeMode ? "この範囲にする" : "登録する", v -> {
            int[] r = cv.selection();
            if (r == null) {
                toast("先に指でなぞって囲んでください");
                return;
            }
            if (rangeMode) {
                prefs().edit().putString("roi", r[0] + "," + r[1] + "," + r[2] + "," + r[3]).apply();
                toast("探す範囲を設定しました");
            } else if (!registerTemplate(shot, r)) {
                return;
            }
            closeCrop(prevState);
        }), weightLp(44));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.bottomMargin = dp(48);
        cropRoot.addView(bar, blp);

        wm.addView(cropRoot, baseLp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, FULL_FLAGS));
    }

    private void removeCrop() {
        if (cropRoot != null) {
            try { wm.removeView(cropRoot); } catch (Exception ignored) { }
            cropRoot = null;
        }
    }

    private void closeCrop(int prevState) {
        removeCrop();
        state = prevState;
        if (panel != null) panel.setVisibility(View.VISIBLE);
        render();
    }

    private boolean registerTemplate(Shot shot, int[] r) {
        int w = r[2] - r[0], h = r[3] - r[1];
        if (w < 40 || h < 40) {
            toast("小さすぎます。もう少し大きく囲んでください");
            return false;
        }
        int[] crop = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(shot.argb, (r[1] + y) * shot.w + r[0], crop, y * w, w);
        ImageMatcher.Template t = ImageMatcher.Template.create(crop, w, h, workFactor(shot.w), true);
        if (!t.usable()) {
            toast("模様が少なすぎて見分けられません。形がわかるように囲んでください");
            return false;
        }
        Bitmap bmp = Bitmap.createBitmap(crop, w, h, Bitmap.Config.ARGB_8888);
        String name = TemplateStore.add(this, bmp);
        if (name == null) {
            toast("保存に失敗しました");
            return false;
        }
        toast("「" + name + "」を登録しました");
        return true;
    }

    /** スクリーンショットを表示して、指で四角を描いてもらうビュー */
    private static final class CropView extends View {
        private final Bitmap bmp;
        private final Paint dim = new Paint(), line = new Paint();
        private final int[] loc = new int[2];
        private float sx, sy, ex, ey;
        private boolean has;

        CropView(Context c, Bitmap b, int stroke) {
            super(c);
            bmp = b;
            dim.setColor(0x99000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            line.setColor(0xFF2FD17A);
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            c.drawBitmap(bmp, -loc[0], -loc[1], null);   // 実際の画面と同じ位置に表示
            if (!has) return;
            float l = Math.min(sx, ex) - loc[0], r = Math.max(sx, ex) - loc[0];
            float t = Math.min(sy, ey) - loc[1], b = Math.max(sy, ey) - loc[1];
            int W = getWidth(), H = getHeight();
            c.drawRect(0, 0, W, t, dim);
            c.drawRect(0, b, W, H, dim);
            c.drawRect(0, t, l, b, dim);
            c.drawRect(r, t, W, b, dim);
            c.drawRect(l, t, r, b, line);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ex = e.getRawX();
                    sy = ey = e.getRawY();
                    has = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    ex = e.getRawX();
                    ey = e.getRawY();
                    break;
            }
            invalidate();
            return true;
        }

        /** 選んだ範囲（画面座標 x0,y0,x1,y1）。小さすぎるときは null */
        int[] selection() {
            if (!has) return null;
            int x0 = clampI((int) Math.min(sx, ex), 0, bmp.getWidth());
            int x1 = clampI((int) Math.max(sx, ex), 0, bmp.getWidth());
            int y0 = clampI((int) Math.min(sy, ey), 0, bmp.getHeight());
            int y1 = clampI((int) Math.max(sy, ey), 0, bmp.getHeight());
            if (x1 - x0 < 8 || y1 - y0 < 8) return null;
            return new int[]{x0, y0, x1, y1};
        }

        private static int clampI(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    // =====================================================================
    // 画像を探してタップ
    // =====================================================================

    private void onScanBtn() {
        if (state == SCAN) {
            abort = true;
        } else if (state == IDLE || state == READY) {
            startScan();
        } else {
            toast("記録・再生を止めてから押してください");
        }
    }

    private void startScan() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("画像の機能は Android 11 以上で使えます");
            return;
        }
        final List<TemplateStore.Tpl> list = new ArrayList<>();
        for (TemplateStore.Tpl t : TemplateStore.load(this)) if (t.enabled) list.add(t);
        if (list.isEmpty()) {
            toast("先に「📷 登録」で画像を登録してください（アプリの一覧で「使う」がオンか確認）");
            return;
        }
        abort = false;
        state = SCAN;
        render();
        new Thread(() -> scanLoop(list)).start();
    }

    private static int[] parseRoi(String s) {
        try {
            String[] a = s.split(",");
            if (a.length != 4) return null;
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) r[i] = Integer.parseInt(a[i].trim());
            return (r[2] > r[0] && r[3] > r[1]) ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    @TargetApi(30)
    private void scanLoop(List<TemplateStore.Tpl> list) {
        SharedPreferences sp = prefs();
        final float thr = sp.getInt("thr", 80) / 100f;
        final long cool = sp.getInt("cool", 5) * 100L;           // 0.1秒単位
        final boolean multi = sp.getBoolean("multi", false);
        final boolean colorCk = sp.getBoolean("color", true);
        final boolean tapAll = sp.getBoolean("tapAll", false);
        final int[] roiScreen = parseRoi(sp.getString("roi", ""));
        final int margin = dp(8);

        int taps = 0, fails = 0;
        String err = null;
        while (!abort) {
            long t0 = SystemClock.uptimeMillis();
            ui.post(this::updatePanelRect);
            Shot shot = takeShotSync(true);
            if (abort) break;
            if (shot == null) {
                if (++fails >= 10) { err = "画面を読み取れません"; break; }
                if (!sleepAbortable(400)) break;
                continue;
            }
            fails = 0;

            int f = workFactor(shot.w);
            ImageMatcher.Gray img = ImageMatcher.Gray.fromArgb(shot.argb, shot.w, 0, 0, shot.w, shot.h, f);
            int[] roi = roiScreen == null ? null
                    : new int[]{roiScreen[0] / f, roiScreen[1] / f, roiScreen[2] / f, roiScreen[3] / f};
            int[] pr = panelRect;

            List<float[]> points = new ArrayList<>();
            String hit = null;
            for (TemplateStore.Tpl tp : list) {                  // 上にある画像ほど優先
                ImageMatcher.Template t = tp.template(f);
                if (t == null) continue;
                List<ImageMatcher.Match> ms = multi
                        ? ImageMatcher.findMultiScale(img, t, roi, thr, 20, SCALES)
                        : ImageMatcher.find(img, t, roi, thr, 20);
                for (ImageMatcher.Match m : ms) {
                    float cx = (m.x + m.w / 2f) * f, cy = (m.y + m.h / 2f) * f;
                    // パネルの上はタップしない
                    if (cx >= pr[0] - margin && cx <= pr[2] + margin && cy >= pr[1] - margin && cy <= pr[3] + margin) continue;
                    if (colorCk && m.colorDiff(shot.argb, shot.w, shot.h, f) > COLOR_TOL) continue;
                    points.add(new float[]{cx, cy});
                    if (!tapAll) break;
                }
                if (!points.isEmpty()) { hit = tp.name; break; }
            }

            if (!points.isEmpty()) {
                for (float[] p : points) {
                    if (abort) break;
                    tapSync(p[0], p[1]);
                    taps++;
                }
                final String msg = "見つけた：" + hit + "（" + points.size() + "か所）";
                final int tc = taps;
                ui.post(() -> setText("🔍 探索中 " + tc + "回", msg));
                if (!sleepAbortable(cool)) break;
            } else {
                final int tc = taps;
                ui.post(() -> setText("🔍 探索中 " + tc + "回", "探しています…"));
            }
            long el = SystemClock.uptimeMillis() - t0;
            if (el < SHOT_INTERVAL && !sleepAbortable(SHOT_INTERVAL - el)) break;
        }
        final int tc = taps;
        final String e = err;
        ui.post(() -> {
            state = actions.isEmpty() ? IDLE : READY;
            render();
            setText(e != null ? e : "探索を止めました", "タップ " + tc + "回");
        });
    }

    // =====================================================================
    // 記録の保存
    // =====================================================================

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Action a : actions) {
                JSONObject o = new JSONObject();
                o.put("t", a.t);
                o.put("d", a.dur);
                JSONArray xs = new JSONArray(), ys = new JSONArray();
                for (int i = 0; i < a.xs.length; i++) {
                    xs.put((double) a.xs[i]);
                    ys.put((double) a.ys[i]);
                }
                o.put("x", xs);
                o.put("y", ys);
                arr.put(o);
            }
            prefs().edit().putString("actions", arr.toString()).putLong("recDur", recDuration).apply();
        } catch (Exception ignored) { }
    }

    private void load() {
        actions.clear();
        try {
            JSONArray arr = new JSONArray(prefs().getString("actions", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Action a = new Action();
                a.t = o.getLong("t");
                a.dur = o.getLong("d");
                JSONArray xs = o.getJSONArray("x"), ys = o.getJSONArray("y");
                a.xs = new float[xs.length()];
                a.ys = new float[ys.length()];
                for (int j = 0; j < xs.length(); j++) {
                    a.xs[j] = (float) xs.getDouble(j);
                    a.ys[j] = (float) ys.getDouble(j);
                }
                if (a.xs.length > 0) actions.add(a);
            }
            recDuration = prefs().getLong("recDur", 0);
        } catch (Exception ignored) {
            actions.clear();
        }
    }
}
