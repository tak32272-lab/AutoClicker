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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ユーザー補助サービス本体。
 * ・画面の上に⏯️パネルを出す
 * ・記録：透明な膜で画面全体のタッチを拾い、座標を記録してから下のアプリへ同じ操作を流す
 * ・再生：記録した操作をまとめて dispatchGesture で再現する（時刻はシステムが正確に守る）
 * ・画像探索：画面から登録画像を探し、見つけたらタップする
 *   高速モード：画面共有で 1 秒に数十回確認（CaptureService）
 *   通常モード：ユーザー補助のスクリーンショットで約 0.34 秒に 1 回（Android 11 以上）
 */
public class ClickerService extends AccessibilityService {

    static final String TAG = "AC";
    static volatile ClickerService instance;

    private static final int IDLE = 0, REC = 1, READY = 2, PLAY = 3, SCAN = 4, BUSY = 5, WAIT = 6;
    private volatile int state = IDLE;

    /** 色の違いがこれより大きければ「別物」とする */
    private static final float COLOR_TOL = 60f;
    /** 大きさの変化に対応するときに試す倍率 */
    private static final float[] SCALES = {0.8f, 1f, 1.25f};
    /** 通常モードのスクリーンショットの間隔（Android の制限で約0.33秒に1回まで） */
    private static final long SHOT_INTERVAL = 340;
    /** 続けてタップするときの、指を離してから次に触るまでの間（ms） */
    private static final int TAP_GAP = 10;
    /** タップがゲームに届くまで＋画面に出るまでの遅れの見込み（ms）。動く敵の予測に使う */
    private static final float EXTRA_LATENCY = 30f;
    /** 記録中、膜をすり抜けにしてからタップを流すまでの待ち（ms） */
    private static final long PASS_DELAY = 34;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread tapThread;
    private Handler tapH;
    private final ExecutorService shotExec = Executors.newSingleThreadExecutor();

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView mainBtn, titleTv, subTv, scanBtn;
    private volatile int[] panelRect = {0, 0, 0, 0};

    private View recLayer;
    private WindowManager.LayoutParams recLp;
    private boolean passing = false, passSelfHit = false;
    private long passAt;
    private Action passAction;

    private FrameLayout cropRoot;

    private final List<Action> actions = new ArrayList<>();
    private long recStart, recDuration;
    private ArrayList<float[]> curPts;
    private long curDown;

    private volatile boolean abort = false;
    private volatile String stats = "";

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
        tapThread = new HandlerThread("ac-tap", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        tapThread.start();
        tapH = new Handler(tapThread.getLooper());
        load();
        state = actions.isEmpty() ? IDLE : READY;
        showPanel();
        Log.i(TAG, "service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { abort = true; }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "service unbind");
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
        stopCapture();
        shotExec.shutdownNow();
        if (tapThread != null) tapThread.quitSafely();
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
        if (state == WAIT) state = actions.isEmpty() ? IDLE : READY;
        removeCrop();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
        panelRect = new int[]{0, 0, 0, 0};
        stopCapture();
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
            case WAIT:  color = 0xFF9B59F6; t = "画面共有の確認中"; s = "「今すぐ開始」を押してください"; break;
            case BUSY:  color = 0xFF3A4553; t = "範囲を選択中"; s = ""; break;
            default:    color = 0xFF3A4553; t = "待機中"; s = "⏯️で記録開始"; break;
        }
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(color);
        mainBtn.setBackground(c);
        scanBtn.setText(state == SCAN || state == WAIT ? "⏹ 停止" : "🔍 探す");
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
            case SCAN:  stopRunning(); break;
            case WAIT:  cancelWait(); break;
            default:    break;
        }
    }

    /** 再生・探索を止める */
    private void stopRunning() {
        abort = true;
        if (state == PLAY) cancelGesture();
    }

    /** 記録したタップを消す */
    private void clearAll() {
        if (state == SCAN || state == BUSY || state == WAIT || state == PLAY) {
            toast("再生・探索を止めてから押してください");
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
    // ジェスチャー（タップ）を送る
    // =====================================================================

    private final AtomicBoolean gestureBusy = new AtomicBoolean(false);
    private volatile long gestureDeadline;

    /** いまジェスチャーを送れるか（前のものが終わっているか） */
    private boolean gestureFree() {
        if (gestureBusy.get() && Frame.nowMs() > gestureDeadline) gestureBusy.set(false);   // 念のため
        return !gestureBusy.get();
    }

    /**
     * ジェスチャーを送る。前のものがまだ動いていれば送らずに false
     * （送ると前のタップが途中で取り消され、ゲームに届かないことがあるため）。
     * @param lengthMs ジェスチャー全体の長さ
     * @param done     終わったとき（取り消されたときも）に呼ぶ。null 可
     */
    private boolean send(GestureDescription g, long lengthMs, final Runnable done) {
        if (!gestureFree() || !gestureBusy.compareAndSet(false, true)) return false;
        gestureDeadline = Frame.nowMs() + lengthMs + 1500;
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { finish(); }
                @Override public void onCancelled(GestureDescription d) { finish(); }

                private void finish() {
                    gestureBusy.set(false);
                    if (done != null) done.run();
                }
            }, tapH);
        } catch (Throwable e) {
            Log.w(TAG, "dispatchGesture failed", e);
            ok = false;
        }
        if (!ok) gestureBusy.set(false);
        return ok;
    }

    /** 送ったジェスチャーが終わるまで待つ（メインスレッド以外から呼ぶ）。中断されたら false */
    private boolean sendAndWait(GestureDescription g, long lengthMs) {
        long until = Frame.nowMs() + 3000;
        while (!gestureFree()) {
            if (abort || Frame.nowMs() > until) return false;
            SystemClock.sleep(2);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        if (!send(g, lengthMs, latch::countDown)) return false;
        try {
            while (!latch.await(20, TimeUnit.MILLISECONDS)) {
                if (abort) {
                    cancelGesture();
                    return false;
                }
            }
        } catch (InterruptedException e) {
            return false;
        }
        return true;
    }

    /** 動いているジェスチャーを止める（パネルの文字の部分を一瞬だけ触る。そこを触っても何も起きない） */
    private void cancelGesture() {
        ui.post(() -> {
            if (titleTv == null || panel == null || !titleTv.isShown()) return;
            int[] loc = new int[2];
            titleTv.getLocationOnScreen(loc);
            Path p = new Path();
            p.moveTo(loc[0] + titleTv.getWidth() / 2f, loc[1] + titleTv.getHeight() / 2f);
            try {
                dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 1)).build(), null, null);
            } catch (Throwable ignored) { }
        });
    }

    private static Path pathOf(Action a) {
        Path p = new Path();
        p.moveTo(a.xs[0], a.ys[0]);
        for (int i = 1; i < a.xs.length; i++) p.lineTo(a.xs[i], a.ys[i]);
        return p;
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
        float x = Math.max(0, e.getRawX());
        float y = Math.max(0, e.getRawY());
        if (passing) {
            // 流したタップが、まだすり抜けになっていない膜に当たってしまった → あとで流し直す
            Action a = passAction;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && a != null
                    && Math.abs(x - a.xs[0]) < 4 && Math.abs(y - a.ys[0]) < 4
                    && Frame.nowMs() - passAt < 600) {
                passSelfHit = true;
            }
            return;
        }
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
                Log.i(TAG, "rec action t=" + a.t + " dur=" + a.dur + " up=" + e.getEventTime() + " pts=" + a.xs.length);
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
    private void passThrough(final Action a) {
        passing = true;
        passSelfHit = false;
        passAction = a;
        recLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recLayer, recLp); } catch (Exception ignored) { }
        ui.postDelayed(() -> dispatchPass(a, false), PASS_DELAY);
    }

    private void dispatchPass(final Action a, final boolean retry) {
        if (recLayer == null || state != REC) {
            endPass();
            return;
        }
        // タップはゲームに届けば十分なので短く流す（すり抜けの時間を短くして、次のタップを記録しやすくする）
        long d = a.xs.length == 1 ? Math.min(a.dur, 40) : Math.min(a.dur, 3000);
        d = Math.max(10, d);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(pathOf(a), 0, d)).build();
        passAt = Frame.nowMs();
        Log.i(TAG, "pass dispatch at=" + SystemClock.uptimeMillis() + (retry ? " retry" : ""));
        boolean ok = send(g, d, () -> ui.post(() -> {
            if (passSelfHit && !retry) {
                Log.i(TAG, "pass self-hit, retry");
                passSelfHit = false;
                ui.postDelayed(() -> dispatchPass(a, true), 40);
                return;
            }
            endPass();
        }));
        if (!ok) endPass();
    }

    private void endPass() {
        passing = false;
        passAction = null;
        if (recLayer != null && state == REC) {
            recLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(recLayer, recLp); } catch (Exception ignored) { }
        }
    }

    private void stopRec() {
        recDuration = SystemClock.uptimeMillis() - recStart;
        removeRecLayer();
        passing = false;
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

    /** 1回のジェスチャーにまとめる操作の最大数と長さ */
    private static final long BATCH_SPAN = 4000;

    private void startPlay() {
        if (actions.isEmpty()) return;
        SharedPreferences sp = prefs();
        final int n = Math.max(1, sp.getInt("count", 100));
        final float mult = Math.max(1, Math.min(100, sp.getInt("speed", 10))) / 10f;  // 10で等速
        final List<Action> list = new ArrayList<>(actions);
        final long total = Math.max(recDuration, list.get(list.size() - 1).t + list.get(list.size() - 1).dur);
        final int maxStrokes = Math.max(1, Math.min(20, GestureDescription.getMaxStrokeCount()));

        abort = false;
        state = PLAY;
        render();

        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            boolean done = true;
            long base = Frame.nowMs() + 30;
            outer:
            for (int k = 0; k < n; k++) {
                final int kk = k + 1;
                ui.post(() -> setText("▶ 再生中 " + kk + "/" + n, "⏯️で中断"));
                long loopStart = base + Math.round(k * total / mult);
                int i = 0;
                while (i < list.size()) {
                    long bStart = Math.round(list.get(i).t / mult);
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    long end = bStart;
                    int strokes = 0, j = i;
                    while (j < list.size() && strokes < maxStrokes) {
                        Action a = list.get(j);
                        long st = Math.round(a.t / mult);
                        if (strokes > 0 && st - bStart > BATCH_SPAN) break;
                        long du = Math.max(1, Math.round(a.dur / mult));
                        if (j + 1 < list.size()) {
                            // 次の操作と重ならないように（重なると同時押しになる）
                            long next = Math.round(list.get(j + 1).t / mult);
                            du = Math.max(1, Math.min(du, next - st - 1));
                        }
                        du = Math.min(du, GestureDescription.getMaxGestureDuration() - (st - bStart) - 1);
                        b.addStroke(new GestureDescription.StrokeDescription(pathOf(a), st - bStart, du));
                        end = Math.max(end, st + du);
                        strokes++;
                        j++;
                    }
                    if (!sleepUntil(loopStart + bStart)) { done = false; break outer; }
                    Log.i(TAG, "play batch loop=" + kk + " from=" + i + " n=" + strokes + " late=" + (Frame.nowMs() - (loopStart + bStart)));
                    if (!sendAndWait(b.build(), end - bStart) && abort) { done = false; break outer; }
                    i = j;
                }
                if (k + 1 < n && !sleepUntil(base + Math.round((k + 1) * total / mult))) { done = false; break; }
            }
            final boolean fin = done && !abort;
            ui.post(() -> {
                state = actions.isEmpty() ? IDLE : READY;
                render();
                if (state == READY) setText(fin ? "再生完了" : "中断しました", "⏯️でもう一度再生");
            });
        }, "ac-play").start();
    }

    /** 指定の時刻（Frame.nowMs 基準）まで待つ。中断されたら false */
    private boolean sleepUntil(long t) {
        while (!abort) {
            long left = t - Frame.nowMs();
            if (left <= 0) return true;
            SystemClock.sleep(Math.min(left > 12 ? left - 8 : 1, 20));
        }
        return false;
    }

    private boolean sleepAbortable(long ms) {
        return sleepUntil(Frame.nowMs() + Math.max(0, ms));
    }

    // =====================================================================
    // スクリーンショット（Android 11 以上）
    // =====================================================================

    private static final class Shot {
        int w, h;
        int[] argb;
    }

    /** 画面を等倍で撮る（終わるまで待つ。メインスレッド以外から呼ぶ）。失敗なら null */
    @TargetApi(30)
    private Shot takeShotSync() {
        final Shot[] out = new Shot[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        Bitmap sw = softCopy(r);
                        if (sw != null) {
                            Shot s = new Shot();
                            s.w = sw.getWidth();
                            s.h = sw.getHeight();
                            s.argb = new int[s.w * s.h];
                            sw.getPixels(s.argb, 0, s.w, 0, 0, s.w, s.h);
                            sw.recycle();
                            out[0] = s;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        hb.close();
                    }
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
    private static Bitmap softCopy(ScreenshotResult r) {
        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
        if (hw == null) return null;
        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
        hw.recycle();
        return sw;
    }

    /** 通常モード：ユーザー補助のスクリーンショットを約0.34秒ごとに撮り、作業解像度に縮めて渡す */
    @TargetApi(30)
    private final class ShotSource implements Frame.Source {
        private final Frame.Exchange ex = new Frame.Exchange();
        private volatile boolean run = true;
        private volatile int fails;

        ShotSource() {
            new Thread(this::loop, "ac-shot").start();
        }

        private void loop() {
            while (run) {
                long t0 = Frame.nowMs();
                final CountDownLatch latch = new CountDownLatch(1);
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult r) {
                            HardwareBuffer hb = r.getHardwareBuffer();
                            try {
                                publish(r, t0);
                            } catch (Throwable e) {
                                fails++;
                            } finally {
                                hb.close();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            fails++;
                            latch.countDown();
                        }
                    });
                    latch.await(3, TimeUnit.SECONDS);
                } catch (Throwable e) {
                    fails++;
                }
                if (fails > 15) break;
                long left = t0 + SHOT_INTERVAL - Frame.nowMs();
                if (left > 0) SystemClock.sleep(left);
            }
            ex.close();
        }

        private void publish(ScreenshotResult r, long t0) {
            Frame fr = ex.free();
            if (fr == null) return;
            Bitmap sw = softCopy(r);
            if (sw == null) { fails++; return; }
            int W = sw.getWidth(), H = sw.getHeight();
            int f = Frame.workFactor(W, H);
            int vw = Math.max(2, Math.round(W / (float) f)), vh = Math.max(2, Math.round(H / (float) f));
            Bitmap small = Bitmap.createScaledBitmap(sw, vw, vh, true);
            fr.ensure(vw, vh);
            small.getPixels(fr.argb, 0, vw, 0, 0, vw, vh);
            if (small != sw) small.recycle();
            sw.recycle();
            fr.sx = W / (float) vw;
            fr.sy = H / (float) vh;
            fr.f = f;
            fr.tsMs = t0;
            fails = 0;
            ex.publish(fr);
        }

        @Override
        public Frame acquire(long afterSeq, long timeoutMs) throws InterruptedException {
            return ex.acquire(afterSeq, timeoutMs);
        }

        @Override public void release(Frame f) { ex.release(f); }
        @Override public boolean alive() { return run && !ex.closed(); }
        @Override public boolean fast() { return false; }

        @Override
        public void close() {
            run = false;
            ex.close();
        }
    }

    // =====================================================================
    // 画像の登録・探す範囲の指定
    // =====================================================================

    private void startCapture(final boolean rangeMode) {
        if (Build.VERSION.SDK_INT < 30) {
            toast("画像の登録は Android 11 以上で使えます");
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
            SystemClock.sleep(300);
            final Shot shot = takeShotSync();
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
        // 画面を縮小するときの区切りとそろえる（ずれると少し見つけにくくなる）
        int f = Frame.workFactor(shot.w, shot.h);
        int x0 = r[0] - r[0] % f, y0 = r[1] - r[1] % f;
        int w = r[2] - x0, h = r[3] - y0;
        if (w < 40 || h < 40) {
            toast("小さすぎます。もう少し大きく囲んでください");
            return false;
        }
        int[] crop = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(shot.argb, (y0 + y) * shot.w + x0, crop, y * w, w);
        ImageMatcher.Template t = ImageMatcher.Template.create(crop, w, h, f, true);
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
        } else if (state == WAIT) {
            cancelWait();
        } else if (state == IDLE || state == READY) {
            requestScan();
        } else {
            toast("記録・再生を止めてから押してください");
        }
    }

    private List<TemplateStore.Tpl> enabledTemplates() {
        final List<TemplateStore.Tpl> list = new ArrayList<>();
        for (TemplateStore.Tpl t : TemplateStore.load(this)) if (t.enabled) list.add(t);
        return list;
    }

    /** 探索を始める。高速モードで画面共有がまだなら、先に許可を求める */
    private void requestScan() {
        if (enabledTemplates().isEmpty()) {
            toast("先に「📷 登録」で画像を登録してください（アプリの一覧で「使う」がオンか確認）");
            return;
        }
        boolean fast = prefs().getBoolean("fast", true);
        CaptureService cs = CaptureService.instance;
        Log.i(TAG, "requestScan fast=" + fast + " capture=" + (cs != null && cs.alive()));
        if (fast && cs != null && cs.alive()) {
            startScan(true);
        } else if (fast) {
            state = WAIT;
            render();
            try {
                startActivity(new Intent(this, ProjectionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (Throwable e) {
                Log.w(TAG, "cannot start ProjectionActivity", e);
                onDenied();
            }
        } else if (Build.VERSION.SDK_INT >= 30) {
            startScan(false);
        } else {
            toast("Android 10 以下では、設定で「高速モード」をオンにしてください");
        }
    }

    private void cancelWait() {
        state = actions.isEmpty() ? IDLE : READY;
        render();
    }

    /** 画面共有の準備ができた（CaptureService から） */
    static void onCaptureReady() {
        final ClickerService s = instance;
        if (s == null) return;
        s.ui.post(() -> {
            if (s.state == WAIT) s.startScan(true);
        });
    }

    /** 画面共有が許可されなかった */
    static void onCaptureDenied() {
        final ClickerService s = instance;
        if (s == null) return;
        s.ui.post(s::onDenied);
    }

    private void onDenied() {
        if (state != WAIT) return;
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "画面共有なし（通常モード）で探します", Toast.LENGTH_SHORT).show();
            startScan(false);
        } else {
            cancelWait();
            Toast.makeText(this, "画面共有が許可されませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    /** 画面共有が終わった（探索中なら通常モードに切り替わる） */
    static void onCaptureStopped() {
        Log.i(TAG, "capture stopped");
    }

    private void stopCapture() {
        if (CaptureService.instance != null) {
            try { stopService(new Intent(this, CaptureService.class)); } catch (Throwable ignored) { }
        }
    }

    private void startScan(boolean fast) {
        final List<TemplateStore.Tpl> list = enabledTemplates();
        if (list.isEmpty()) {
            cancelWait();
            return;
        }
        Frame.Source src = null;
        CaptureService cs = CaptureService.instance;
        if (fast && cs != null && cs.alive()) src = new CaptureService.Src(cs);
        else if (Build.VERSION.SDK_INT >= 30) src = new ShotSource();
        if (src == null) {
            cancelWait();
            toast("画面を読み取れません");
            return;
        }
        abort = false;
        state = SCAN;
        render();
        Log.i(TAG, "startScan fast=" + src.fast() + " templates=" + list.size());
        final Frame.Source s0 = src;
        new Thread(() -> scanLoop(list, s0), "ac-scan").start();
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

    private void scanLoop(List<TemplateStore.Tpl> list, Frame.Source src) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        SharedPreferences sp = prefs();
        final float thr = sp.getInt("thr", 80) / 100f;
        final long cool = sp.getInt("cool", 3) * 100L;           // 0.1秒単位
        final boolean multi = sp.getBoolean("multi", false);
        final boolean colorCk = sp.getBoolean("color", true);
        final boolean tapAll = sp.getBoolean("tapAll", false);
        final boolean predict = sp.getBoolean("predict", true);
        final int tapMs = Math.max(10, Math.min(300, sp.getInt("tapMs", 40)));
        final int[] roiScreen = parseRoi(sp.getString("roi", ""));
        final int margin = dp(8);

        Tracker tracker = new Tracker();
        ImageMatcher.Pyramid pyr = new ImageMatcher.Pyramid();
        List<Tracker.Det> dets = new ArrayList<>();
        long lastSeq = -1, lastUi = 0, lastLog = 0, lastFreshAt = 0;
        int taps = 0, empty = 0, frames = 0;
        float fps = 0, procMs = 0, reactMs = 0;
        String err = null;
        try {
            while (!abort) {
                if (!src.alive()) {
                    // 画面共有が止まった → 通常モードで続ける
                    src.close();
                    if (Build.VERSION.SDK_INT >= 30 && src.fast()) {
                        src = new ShotSource();
                        toast("画面共有が終わったので通常モードで続けます");
                        lastSeq = -1;
                        continue;
                    }
                    err = "画面を読み取れません";
                    break;
                }
                Frame fr = src.acquire(lastSeq, src.fast() ? 120 : 200);
                if (abort) {
                    if (fr != null) src.release(fr);
                    break;
                }
                if (fr == null) {
                    if (++empty > 50) { err = "画面を読み取れません"; break; }
                    continue;
                }
                empty = 0;
                boolean fresh = fr.seq != lastSeq;
                lastSeq = fr.seq;
                long t0 = Frame.nowMs();
                float sw = fr.w * fr.sx, sh = fr.h * fr.sy;
                try {
                    if (fresh) {
                        pyr.build(fr.argb, fr.w, fr.h);
                        int[] roi = roiScreen == null ? null : new int[]{
                                (int) (roiScreen[0] / fr.sx), (int) (roiScreen[1] / fr.sy),
                                (int) Math.ceil(roiScreen[2] / fr.sx), (int) Math.ceil(roiScreen[3] / fr.sy)};
                        int[] pr = panelRect;
                        dets = new ArrayList<>();
                        for (int ti = 0; ti < list.size(); ti++) {
                            ImageMatcher.Template t = list.get(ti).template(fr.f);
                            if (t == null) continue;
                            List<ImageMatcher.Match> ms = multi
                                    ? ImageMatcher.findMultiScale(pyr, t, roi, thr, 20, SCALES)
                                    : ImageMatcher.find(pyr, t, roi, thr, 20);
                            for (ImageMatcher.Match m : ms) {
                                float cx = (m.x + m.w / 2f) * fr.sx, cy = (m.y + m.h / 2f) * fr.sy;
                                // パネルの上はタップしない
                                if (cx >= pr[0] - margin && cx <= pr[2] + margin && cy >= pr[1] - margin && cy <= pr[3] + margin) continue;
                                if (colorCk && m.colorDiff(fr.argb, fr.w, fr.h) > COLOR_TOL) continue;
                                dets.add(new Tracker.Det(ti, cx, cy, m.w * fr.sx, m.h * fr.sy, m.score));
                            }
                        }
                    }
                    tracker.update(dets, fr.tsMs);
                } finally {
                    src.release(fr);
                }
                long now = Frame.nowMs();
                if (fresh) {
                    frames++;
                    procMs = procMs == 0 ? now - t0 : procMs * 0.85f + (now - t0) * 0.15f;
                    if (lastFreshAt > 0) {
                        float inst = 1000f / Math.max(1, now - lastFreshAt);
                        fps = fps == 0 ? inst : fps * 0.85f + inst * 0.15f;
                    }
                    lastFreshAt = now;
                }

                // タップ（前のタップが終わっていれば）
                if (gestureFree()) {
                    List<Tracker.Track> cand = tracker.tappable(now, cool);
                    if (!cand.isEmpty()) {
                        int[] pr = panelRect;
                        GestureDescription.Builder b = new GestureDescription.Builder();
                        List<Tracker.Track> used = new ArrayList<>();
                        long len = 0;
                        int limit = tapAll ? 10 : 1;
                        for (Tracker.Track k : cand) {
                            if (used.size() >= limit) break;
                            long start = (long) used.size() * (tapMs + TAP_GAP);
                            float horizon = (now - k.last) + start + EXTRA_LATENCY;
                            float[] p = Tracker.predict(k, horizon, predict);
                            float px = Math.max(1, Math.min(sw - 2, p[0])), py = Math.max(1, Math.min(sh - 2, p[1]));
                            if (px >= pr[0] && px <= pr[2] && py >= pr[1] && py <= pr[3]) continue;
                            Path path = new Path();
                            path.moveTo(px, py);
                            b.addStroke(new GestureDescription.StrokeDescription(path, start, tapMs));
                            used.add(k);
                            len = start + tapMs;
                        }
                        if (!used.isEmpty() && send(b.build(), len, null)) {
                            for (Tracker.Track k : used) k.lastTap = now;
                            taps += used.size();
                            float r = now - used.get(0).last;
                            reactMs = reactMs == 0 ? r : reactMs * 0.8f + r * 0.2f;
                            Tracker.Track k0 = used.get(0);
                            Log.i(TAG, String.format(Locale.US, "tap n=%d at=%d frameAge=%.0f id=%d obs=%d v=%.2f,%.2f",
                                    used.size(), SystemClock.uptimeMillis(), r, k0.id, k0.obs, k0.vx, k0.vy));
                        }
                    }
                }

                if (now - lastUi > 300) {
                    lastUi = now;
                    ui.post(this::updatePanelRect);
                    final String title = "🔍 探索中 " + taps + "回";
                    final String sub = String.format(Locale.US, "%s %.0f回/秒・反応%.0fms",
                            src.fast() ? "⚡高速" : "通常", fps, reactMs + fr2frame(fps));
                    stats = String.format(Locale.US, "mode=%s fps=%.1f proc=%.1fms react=%.0fms taps=%d frames=%d tracks=%d",
                            src.fast() ? "fast" : "slow", fps, procMs, reactMs, taps, frames, tracker.tracks().size());
                    ui.post(() -> { if (state == SCAN) setText(title, sub); });
                }
                if (now - lastLog > 2000) {
                    lastLog = now;
                    Log.i(TAG, "scan " + stats);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable e) {
            Log.e(TAG, "scan error", e);
            err = "エラーで止まりました";
        } finally {
            src.close();
        }
        final int tc = taps;
        final String e = err;
        Log.i(TAG, "scan end " + stats);
        ui.post(() -> {
            state = actions.isEmpty() ? IDLE : READY;
            render();
            setText(e != null ? e : "探索を止めました", "タップ " + tc + "回");
        });
    }

    /** 画面に出てから見つけるまでの平均の待ち（フレーム間隔の半分） */
    private static float fr2frame(float fps) {
        return fps > 0.1f ? 500f / fps : 0f;
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

    // =====================================================================
    // 動作確認用（adb shell dumpsys activity service ... から呼べる。DUMP 権限が必要なので普通のアプリからは呼べない）
    // =====================================================================

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        String cmd = (args != null && args.length > 0) ? args[0] : "";
        final String a1 = (args != null && args.length > 1) ? args[1] : "";
        final String a2 = (args != null && args.length > 2) ? args[2] : "";
        switch (cmd) {
            case "scan":
                ui.post(() -> {
                    if (a1.equals("fast")) prefs().edit().putBoolean("fast", true).commit();
                    if (a1.equals("slow")) prefs().edit().putBoolean("fast", false).commit();
                    if (state == IDLE || state == READY) requestScan();
                });
                break;
            case "stop":
                ui.post(() -> {
                    if (state == WAIT) cancelWait();
                    else if (state == SCAN || state == PLAY) stopRunning();
                });
                break;
            case "rec":
                ui.post(() -> { if (state == IDLE || state == READY) startRec(); });
                break;
            case "stoprec":
                ui.post(() -> { if (state == REC) stopRec(); });
                break;
            case "play":
                ui.post(() -> { if (state == READY) startPlay(); });
                break;
            case "panel":
                ui.post(this::showPanel);
                break;
            case "endcapture":
                ui.post(this::stopCapture);
                break;
            case "set":
                if (!a1.isEmpty()) {
                    SharedPreferences.Editor e = prefs().edit();
                    if (a2.equals("true") || a2.equals("false")) e.putBoolean(a1, Boolean.parseBoolean(a2));
                    else {
                        try { e.putInt(a1, Integer.parseInt(a2)); } catch (NumberFormatException ex) { e.putString(a1, a2); }
                    }
                    e.commit();
                    ui.post(this::render);
                }
                break;
            default:
                break;
        }
        int[] pr = panelRect;
        pw.println("state=" + state + " actions=" + actions.size() + " capture=" + (CaptureService.instance != null)
                + " panel=" + pr[0] + "," + pr[1] + "," + pr[2] + "," + pr[3]);
        pw.println("stats " + stats);
        pw.flush();
    }
}
