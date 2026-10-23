package com.example.testgame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 自動テスト用のゲーム。
 *  mode=template : 怪物を1体だけ止めて表示（登録画像を作る用）
 *  mode=scan     : 怪物が出てくる。タップされたら消えて、別の場所にまた出る（反応時間と命中を記録）
 *  mode=touch    : タッチを記録するだけ（記録・再生のテスト用）
 * すべて logcat の TG タグに書く。時刻は SystemClock.uptimeMillis。
 */
public class GameActivity extends Activity {

    static final String TAG = "TG";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(new GameView(this, getIntent()));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    static final class Monster {
        int id;
        float x0, y0, vx, vy;     // 出た時刻の位置と速さ（px/ms）
        long born, visibleAt = -1;
        boolean blue;
    }

    static final class GameView extends View {
        final String mode;
        final int speed, alive, life, total;
        final float R = 75f;
        final Random rnd;
        final List<Monster> ms = new ArrayList<>();
        final List<Long> spawnAt = new ArrayList<>();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap bg;
        int nextId = 1, spawned = 0;
        float minX, maxX, minY, maxY;
        final int[] loc = new int[2];
        float lastTx = -1, lastTy = -1;

        GameView(Context c, Intent i) {
            super(c);
            String m = i.getStringExtra("mode");
            mode = m == null ? "scan" : m;
            speed = i.getIntExtra("speed", 0);
            alive = Math.max(1, i.getIntExtra("alive", 1));
            life = i.getIntExtra("life", 3000);
            total = i.getIntExtra("total", 100000);
            rnd = new Random(i.getIntExtra("seed", 5));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bg = makeBg(w, h);
            minX = R + 30;
            maxX = w - R - 30;
            minY = Math.max(h * 0.35f, R + 110);
            maxY = h - R - 60;
            ms.clear();
            spawnAt.clear();
            long now = SystemClock.uptimeMillis();
            if (mode.equals("template")) {
                Monster mo = new Monster();
                mo.id = 0;
                mo.x0 = Math.round(w / 2f);
                mo.y0 = Math.round(h * 0.55f);
                mo.born = now;
                ms.add(mo);
            } else if (mode.equals("scan")) {
                for (int k = 0; k < alive; k++) spawnAt.add(now + 1500 + k * 400L);
            }
        }

        private Bitmap makeBg(int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Random r = new Random(7);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int y = 0; y < h; y += 120)
                for (int x = 0; x < w; x += 120) {
                    int v = 70 + r.nextInt(50);
                    q.setColor(Color.rgb(cl(v - 10 + r.nextInt(20)), cl(v + 20 + r.nextInt(20)), cl(v - 20 + r.nextInt(20))));
                    c.drawRect(x, y, x + 120, y + 120, q);
                }
            q.setStrokeWidth(2f);
            for (int k = 0; k < 4000; k++) {
                int x = r.nextInt(w), y = r.nextInt(h);
                q.setColor(Color.rgb(cl(40 + r.nextInt(60)), cl(100 + r.nextInt(80)), cl(30 + r.nextInt(50))));
                c.drawLine(x, y, x + r.nextInt(9) - 4, y - 6 - r.nextInt(12), q);
            }
            for (int k = 0; k < 60; k++) {
                int x = r.nextInt(w), y = r.nextInt(h), s = 20 + r.nextInt(50);
                int g = 100 + r.nextInt(80);
                q.setColor(Color.rgb(g, g, g));
                c.drawOval(new RectF(x, y, x + s, y + s * 0.7f), q);
            }
            return b;
        }

        static int cl(int v) { return Math.max(0, Math.min(255, v)); }

        static float reflect(float pos, float lo, float hi) {
            float span = hi - lo, per = 2 * span;
            float u = ((pos - lo) % per + per) % per;
            return lo + (u <= span ? u : per - u);
        }

        float mx(Monster m, long t) { return reflect(m.x0 + m.vx * (t - m.born), minX, maxX); }
        float my(Monster m, long t) { return reflect(m.y0 + m.vy * (t - m.born), minY, maxY); }

        /** その時刻の進む向き（反射を考えた速度） */
        float[] vel(Monster m, long t) {
            float ax = mx(m, t), bx = mx(m, t + 5), ay = my(m, t), by = my(m, t + 5);
            return new float[]{(bx - ax) / 5f, (by - ay) / 5f};
        }

        void spawn(long now) {
            Monster m = new Monster();
            m.id = nextId++;
            for (int tries = 0; tries < 30; tries++) {
                m.x0 = minX + rnd.nextFloat() * (maxX - minX);
                m.y0 = minY + rnd.nextFloat() * (maxY - minY);
                boolean ok = true;
                for (Monster o : ms) if (Math.hypot(mx(o, now) - m.x0, my(o, now) - m.y0) < 320) ok = false;
                if (ok) break;
            }
            double ang = rnd.nextDouble() * Math.PI * 2;
            m.vx = (float) (Math.cos(ang) * speed / 1000.0);
            m.vy = (float) (Math.sin(ang) * speed / 1000.0);
            m.born = now;
            ms.add(m);
            spawned++;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            getLocationOnScreen(loc);
            if (bg != null) c.drawBitmap(bg, 0, 0, null);
            if (mode.equals("scan")) {
                for (int i = spawnAt.size() - 1; i >= 0; i--) {
                    if (now >= spawnAt.get(i) && spawned < total) {
                        spawnAt.remove(i);
                        spawn(now);
                    }
                }
                for (int i = ms.size() - 1; i >= 0; i--) {
                    Monster m = ms.get(i);
                    if (m.visibleAt >= 0 && now - m.visibleAt > life) {
                        Log.i(TAG, String.format(Locale.US, "TIMEOUT id=%d t=%d", m.id, now));
                        ms.remove(i);
                        spawnAt.add(now + 300 + rnd.nextInt(400));
                    }
                }
            }
            for (Monster m : ms) {
                float x = mx(m, now), y = my(m, now);
                drawMonster(c, x, y, m.blue);
                if (m.visibleAt < 0) {
                    m.visibleAt = now;
                    if (mode.equals("template")) {
                        Log.i(TAG, String.format(Locale.US, "TPL cx=%.0f cy=%.0f", x + loc[0], y + loc[1]));
                    } else {
                        Log.i(TAG, String.format(Locale.US, "SPAWN id=%d t=%d x=%.0f y=%.0f speed=%d", m.id, now, x + loc[0], y + loc[1], speed));
                    }
                }
            }
            if (mode.equals("touch") && lastTx >= 0) {
                p.setColor(Color.RED);
                p.setStrokeWidth(4);
                c.drawLine(lastTx - 30, lastTy, lastTx + 30, lastTy, p);
                c.drawLine(lastTx, lastTy - 30, lastTx, lastTy + 30, p);
            }
            postInvalidateOnAnimation();
        }

        void drawMonster(Canvas c, float cx, float cy, boolean blue) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(240, 200, 40));
            Path h1 = new Path();
            h1.moveTo(cx - 50, cy - 45); h1.lineTo(cx - 35, cy - 100); h1.lineTo(cx - 15, cy - 60); h1.close();
            Path h2 = new Path();
            h2.moveTo(cx + 50, cy - 45); h2.lineTo(cx + 35, cy - 100); h2.lineTo(cx + 15, cy - 60); h2.close();
            c.drawPath(h1, p);
            c.drawPath(h2, p);
            p.setColor(blue ? Color.rgb(60, 107, 176) : Color.rgb(60, 176, 67));
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(6f);
            p.setColor(blue ? Color.rgb(20, 40, 90) : Color.rgb(20, 90, 30));
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawCircle(cx - 28, cy - 15, 18, p);
            c.drawCircle(cx + 28, cy - 15, 18, p);
            p.setColor(Color.BLACK);
            c.drawCircle(cx - 24, cy - 12, 8, p);
            c.drawCircle(cx + 32, cy - 12, 8, p);
            p.setColor(Color.rgb(200, 40, 40));
            c.drawArc(new RectF(cx - 30, cy + 10, cx + 30, cy + 50), 0, 180, false, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int act = e.getActionMasked();
            int idx = e.getActionIndex();
            String an = act == MotionEvent.ACTION_DOWN ? "DOWN" : act == MotionEvent.ACTION_UP ? "UP"
                    : act == MotionEvent.ACTION_POINTER_DOWN ? "PDOWN" : act == MotionEvent.ACTION_POINTER_UP ? "PUP"
                    : act == MotionEvent.ACTION_CANCEL ? "CANCEL" : act == MotionEvent.ACTION_MOVE ? "MOVE" : "A" + act;
            if (act != MotionEvent.ACTION_MOVE) {
                Log.i(TAG, String.format(Locale.US, "TOUCH %s t=%d x=%.0f y=%.0f n=%d", an, e.getEventTime(),
                        e.getX(idx) + loc[0], e.getY(idx) + loc[1], e.getPointerCount()));
            }
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
                float tx = e.getX(idx), ty = e.getY(idx);
                lastTx = tx;
                lastTy = ty;
                // 実際のゲームと同じく、タッチを受け取って処理した時刻の位置で当たり判定する
                long t = SystemClock.uptimeMillis();
                if (mode.equals("scan")) {
                    Monster best = null;
                    float bd = Float.MAX_VALUE;
                    for (Monster m : ms) {
                        if (m.visibleAt < 0) continue;
                        float d = (float) Math.hypot(tx - mx(m, t), ty - my(m, t));
                        if (d < bd) { bd = d; best = m; }
                    }
                    if (best != null) {
                        float[] v = vel(best, t);
                        float sp = (float) Math.hypot(v[0], v[1]);
                        float along = sp > 1e-4 ? ((tx - mx(best, t)) * v[0] + (ty - my(best, t)) * v[1]) / sp : 0;
                        if (bd <= R) {
                            Log.i(TAG, String.format(Locale.US, "HIT id=%d t=%d react=%d dist=%.0f along=%.0f",
                                    best.id, t, t - best.visibleAt, bd, along));
                            ms.remove(best);
                            spawnAt.add(SystemClock.uptimeMillis() + 300 + rnd.nextInt(400));
                        } else {
                            Log.i(TAG, String.format(Locale.US, "MISS t=%d near=%d dist=%.0f along=%.0f", t, best.id, bd, along));
                        }
                    } else {
                        Log.i(TAG, String.format(Locale.US, "MISS t=%d near=-1", t));
                    }
                }
            }
            return true;
        }
    }
}
