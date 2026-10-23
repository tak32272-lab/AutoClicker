package com.example.autoclicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 見つけた敵をフレームごとに追いかける（Android の機能は使っていないので PC でもテストできる）。
 *  ・同じ敵かどうかを位置の近さで判断し、動く速さ（速度）を求める
 *  ・タップした敵は「同じ敵を続けてタップする間隔」が過ぎるまでタップしない（ほかの敵はすぐタップできる）
 *  ・タップが届くまでの時間ぶん、動く先を予測した位置を返す
 * 座標・大きさは画面のピクセル、時間はミリ秒。
 */
public final class Tracker {

    /** 1フレームで見つけたもの */
    public static final class Det {
        final int tpl;
        final float x, y, w, h, score;

        public Det(int tpl, float x, float y, float w, float h, float score) {
            this.tpl = tpl; this.x = x; this.y = y; this.w = w; this.h = h; this.score = score;
        }
    }

    /** 追いかけている敵 */
    public static final class Track {
        public final int id, tpl;
        public float x, y, w, h, score;
        public float vx, vy;               // ピクセル/ミリ秒
        public long first, last;           // 最初・最後に見えた時刻
        public int obs;                    // 見えた回数
        public long lastTap = Long.MIN_VALUE / 4;
        public boolean seen;               // いちばん新しいフレームで見えたか
        int vel;                           // 速度を求めた回数

        Track(int id, Det d, long t) {
            this.id = id;
            this.tpl = d.tpl;
            x = d.x; y = d.y; w = d.w; h = d.h; score = d.score;
            first = last = t;
            obs = 1;
            seen = true;
        }

        public float speed() { return (float) Math.hypot(vx, vy); }
    }

    /** 速さの上限（ピクセル/ミリ秒）。これより速いのは見間違いとみなす */
    static final float MAX_SPEED = 6f;

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private float frameMs = 33f;           // フレームの間隔（平均）
    private long prevT = Long.MIN_VALUE;

    public List<Track> tracks() { return tracks; }

    public float frameInterval() { return frameMs; }

    /** 見えなくなってから、別の敵とみなすまでの時間 */
    private long lostMs() {
        return (long) Math.max(250, frameMs * 3.5f);
    }

    /**
     * 新しいフレームの結果で更新する。
     * 同じフレームをもう一度処理したとき（t が前回と同じ）は、見えているかの印だけ付け直す。
     */
    public void update(List<Det> dets, long t) {
        if (prevT != Long.MIN_VALUE && t > prevT) {
            float dt = t - prevT;
            if (dt < 2000) frameMs = frameMs * 0.8f + dt * 0.2f;
        }
        if (t > prevT) prevT = t;

        for (Track k : tracks) k.seen = false;

        // 近い組み合わせから順に対応づける
        List<float[]> pairs = new ArrayList<>();
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track k = tracks.get(ti);
            long dt = t - k.last;
            if (dt < 0) continue;
            float px = k.x + k.vx * dt, py = k.y + k.vy * dt;
            float size = Math.max(k.w, k.h);
            float gate = size * 0.7f + k.speed() * dt * 0.5f + 12f;
            for (int di = 0; di < dets.size(); di++) {
                Det d = dets.get(di);
                if (d.tpl != k.tpl) continue;
                float dist = (float) Math.hypot(d.x - px, d.y - py);
                if (dist <= gate) pairs.add(new float[]{dist, ti, di});
            }
        }
        Collections.sort(pairs, (a, b) -> Float.compare(a[0], b[0]));
        boolean[] usedT = new boolean[tracks.size()], usedD = new boolean[dets.size()];
        for (float[] p : pairs) {
            int ti = (int) p[1], di = (int) p[2];
            if (usedT[ti] || usedD[di]) continue;
            usedT[ti] = true;
            usedD[di] = true;
            follow(tracks.get(ti), dets.get(di), t);
        }
        for (int di = 0; di < dets.size(); di++) {
            if (!usedD[di]) tracks.add(new Track(nextId++, dets.get(di), t));
        }
        // しばらく見えない敵は忘れる
        long lost = lostMs();
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (t - tracks.get(i).last > lost) tracks.remove(i);
        }
    }

    private void follow(Track k, Det d, long t) {
        long dt = t - k.last;
        if (dt > lostMs() * 0.6f) {
            // 長く見えなかった後に同じ場所に出た → 新しい敵とみなす（すぐタップできるように）
            k.lastTap = Long.MIN_VALUE / 4;
            k.vx = k.vy = 0;
            k.vel = 0;
            k.first = t;
        } else if (dt >= 4) {
            float nvx = (d.x - k.x) / dt, nvy = (d.y - k.y) / dt;
            float sp = (float) Math.hypot(nvx, nvy);
            if (sp > MAX_SPEED) { nvx *= MAX_SPEED / sp; nvy *= MAX_SPEED / sp; }
            if (k.vel == 0) { k.vx = nvx; k.vy = nvy; }
            else { k.vx = k.vx * 0.4f + nvx * 0.6f; k.vy = k.vy * 0.4f + nvy * 0.6f; }
            k.vel++;
        }
        k.x = d.x; k.y = d.y; k.w = d.w; k.h = d.h; k.score = d.score;
        if (dt > 0) k.obs++;
        k.last = Math.max(k.last, t);
        k.seen = true;
    }

    /**
     * horizonMs 後にいるはずの位置。速度がまだわからない・予測しない設定のときは今の位置。
     * 予測は敵の大きさの 1.2 倍までにおさえる（急に向きを変えたときに大きく外さないように）。
     */
    public static float[] predict(Track k, float horizonMs, boolean enable) {
        if (!enable || k.vel < 1 || horizonMs <= 0) return new float[]{k.x, k.y};
        float lx = k.vx * horizonMs, ly = k.vy * horizonMs;
        float cap = Math.max(k.w, k.h) * 1.2f;
        float len = (float) Math.hypot(lx, ly);
        if (len > cap) { lx *= cap / len; ly *= cap / len; }
        return new float[]{k.x + lx, k.y + ly};
    }

    /**
     * 今タップしてよい敵を、優先する順（登録画像の順 → 似ている度合いの順）に返す。
     * @param coolMs 同じ敵を続けてタップする間隔
     */
    public List<Track> tappable(long now, long coolMs) {
        List<Track> out = new ArrayList<>();
        for (Track k : tracks) {
            if (!k.seen) continue;
            if (now - k.lastTap < coolMs) continue;
            out.add(k);
        }
        Collections.sort(out, (a, b) -> a.tpl != b.tpl ? Integer.compare(a.tpl, b.tpl) : Float.compare(b.score, a.score));
        return out;
    }
}
