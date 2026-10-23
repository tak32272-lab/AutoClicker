package com.example.autoclicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音ゲーモード（太鼓）の頭脳部分。Android の機能は使っていないので PC でもテストできる。
 *  ・流れてくる音符を毎フレーム受け取り、全体の流れる速さ（向きつき）を求める
 *  ・音符ごとに追いかけ、判定の丸に届く時刻を計算する
 *  ・届く時刻にタップが間に合うよう、少し先までのタップの予定をまとめて返す
 *  ・連打・風船は「判定の丸に来ている間」と「判定の丸から後ろへ続く長さ」から終わりの時刻を見積もる
 * 座標は画面のピクセル、時刻は Frame.nowMs と同じ基準のミリ秒。
 */
public final class RhythmPlanner {

    public static final int DON = 1, KA = 2, ROLL = 3;

    /** 1フレームで見つけた音符 */
    public static final class Note {
        final int kind;
        final float x, y, size;

        public Note(int kind, float x, float y, float size) {
            this.kind = kind; this.x = x; this.y = y; this.size = size;
        }
    }

    /** 追いかけている音符 */
    static final class Entry {
        final int id, kind;
        float x, y, size;      // 最後に見えた位置
        long t;                // 最後に見えた時刻
        int n;                 // 見えた回数
        boolean sent;          // タップを予約済み

        Entry(int id, Note nt, long t) {
            this.id = id; this.kind = nt.kind; x = nt.x; y = nt.y; size = nt.size; this.t = t; n = 1;
        }
    }

    /** 予定したタップ */
    public static final class Tap {
        public final long at;      // ゲームに届いてほしい時刻
        public final int kind;     // DON / KA（連打は DON の場所を叩く）

        Tap(long at, int kind) { this.at = at; this.kind = kind; }
    }

    private final float jx, jy;
    private double vx, vy;               // 流れる速さ（ピクセル/ミリ秒）
    private boolean vKnown;
    private int vOutliers;
    private List<Note> prev = new ArrayList<>();
    private long prevT = Long.MIN_VALUE;
    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;
    private float frameMs = 33f;

    // 連打・風船
    private long rollUntil = Long.MIN_VALUE;     // ここまで連打する（届いてほしい時刻の基準）
    private long rollNext = Long.MIN_VALUE;      // 次の連打の時刻
    public int dons, kas, rollTaps;

    public RhythmPlanner(float judgeX, float judgeY) {
        jx = judgeX;
        jy = judgeY;
    }

    public boolean speedKnown() { return vKnown; }

    public double vx() { return vx; }

    public double vy() { return vy; }

    public double speed() { return Math.hypot(vx, vy); }

    public int tracked() { return entries.size(); }

    /**
     * フレームの結果を入れる。
     * @param t          ゲームがこの画面を描いた時刻（見積もり）
     * @param rollAtJudge 判定の丸の所に連打・風船の色があるか
     * @param rollTailPx  判定の丸から、音符が来る向きへ続く連打の長さ（なければ -1）
     */
    public void update(List<Note> notes, long t, boolean rollAtJudge, float rollTailPx) {
        if (t <= prevT) {
            // 同じフレームを処理し直しただけ
            return;
        }
        if (prevT != Long.MIN_VALUE) {
            float dt = t - prevT;
            if (dt < 1000) frameMs = frameMs * 0.8f + dt * 0.2f;
            if (dt > 0 && dt < 500 && !vKnown) estimateSpeed(prev, notes, dt);
        }
        prev = notes;
        prevT = t;

        if (vKnown) follow(notes, t);

        // 連打・風船の終わり
        double sp = speed();
        if (rollAtJudge) {
            long until = t + (long) (frameMs * 2.2f);
            if (rollTailPx > 0 && sp > 1e-3) until = Math.max(until, t + (long) (rollTailPx / sp));
            rollUntil = Math.max(rollUntil, until);
        }
        // 過ぎた音符は忘れる
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (hitTime(e) < t - 400 || t - e.t > 4000) entries.remove(i);
        }
    }

    /**
     * 速さがまだわからないとき：前のフレームとの音符の動きの多数決で流れる速さを求める。
     * 2回続けて同じくらいの値になったら決める。
     */
    private double[] candV;

    private void estimateSpeed(List<Note> a, List<Note> b, float dt) {
        if (vKnown) return;
        List<double[]> c = new ArrayList<>();
        for (Note p : a)
            for (Note q : b) {
                if (p.kind != q.kind) continue;
                double dx = q.x - p.x, dy = q.y - p.y;
                double d = Math.hypot(dx, dy);
                if (d < 1.5 || d / dt > 5.0) continue;                       // 止まっている／速すぎる
                if ((jx - p.x) * dx + (jy - p.y) * dy <= 0) continue;       // 判定の丸へ向かっていない
                c.add(new double[]{dx / dt, dy / dt});
            }
        if (c.isEmpty()) return;
        double tol = Math.max(0.05, 6.0 / dt);
        int best = -1, bestCnt = 0;
        double bestSp = Double.MAX_VALUE;
        for (int i = 0; i < c.size(); i++) {
            double[] ci = c.get(i);
            int cnt = 0;
            for (double[] cj : c) if (Math.hypot(cj[0] - ci[0], cj[1] - ci[1]) <= tol) cnt++;
            double sp = Math.hypot(ci[0], ci[1]);
            // 一番多いもの。同じくらいなら遅い方（隣の音符と取り違えた組は速く見える）
            if (cnt > bestCnt || (cnt == bestCnt && sp < bestSp)) { bestCnt = cnt; best = i; bestSp = sp; }
        }
        double[] cb = c.get(best);
        double sx = 0, sy = 0;
        int n = 0;
        for (double[] cj : c) if (Math.hypot(cj[0] - cb[0], cj[1] - cb[1]) <= tol) { sx += cj[0]; sy += cj[1]; n++; }
        double[] nv = {sx / n, sy / n};
        if (candV != null && Math.hypot(nv[0] - candV[0], nv[1] - candV[1]) <= Math.hypot(candV[0], candV[1]) * 0.15) {
            vx = (candV[0] + nv[0]) / 2;
            vy = (candV[1] + nv[1]) / 2;
            vKnown = true;
            candV = null;
        } else {
            candV = nv;
        }
    }

    /** 音符を前のフレームの音符と対応づけて追いかける。対応づいた音符の動きで速さも少しずつ直す */
    private void follow(List<Note> notes, long t) {
        List<double[]> pairs = new ArrayList<>();
        for (int ei = 0; ei < entries.size(); ei++) {
            Entry e = entries.get(ei);
            long dt = t - e.t;
            double px = e.x + vx * dt, py = e.y + vy * dt;
            for (int ni = 0; ni < notes.size(); ni++) {
                Note nt = notes.get(ni);
                if (nt.kind != e.kind) continue;
                double d = Math.hypot(nt.x - px, nt.y - py);
                double gate = Math.max(12, 0.3 * Math.min(e.size, nt.size)) + Math.min(dt, 200) * 0.02;
                if (d <= gate) pairs.add(new double[]{d, ei, ni});
            }
        }
        Collections.sort(pairs, (p, q) -> Double.compare(p[0], q[0]));
        boolean[] ue = new boolean[entries.size()], un = new boolean[notes.size()];
        List<double[]> vs = new ArrayList<>();
        for (double[] p : pairs) {
            int ei = (int) p[1], ni = (int) p[2];
            if (ue[ei] || un[ni]) continue;
            ue[ei] = un[ni] = true;
            Entry e = entries.get(ei);
            Note nt = notes.get(ni);
            long dt = t - e.t;
            if (dt >= 15 && dt <= 300) vs.add(new double[]{(nt.x - e.x) / dt, (nt.y - e.y) / dt});
            e.x = nt.x; e.y = nt.y; e.size = nt.size; e.t = t; e.n++;
        }
        if (!vs.isEmpty()) {
            double[] xs = new double[vs.size()], ys = new double[vs.size()];
            for (int i = 0; i < vs.size(); i++) { xs[i] = vs.get(i)[0]; ys[i] = vs.get(i)[1]; }
            java.util.Arrays.sort(xs);
            java.util.Arrays.sort(ys);
            double mx = xs[xs.length / 2], my = ys[ys.length / 2];
            double cur = Math.hypot(vx, vy);
            if (Math.hypot(mx - vx, my - vy) <= cur * 0.3) {
                double w = Math.min(0.3, 0.06 * vs.size());
                vx = vx * (1 - w) + mx * w;
                vy = vy * (1 - w) + my * w;
                vOutliers = 0;
            } else if (++vOutliers >= 4) {
                vKnown = false;                    // 速さが変わった → 測り直す
                vOutliers = 0;
            }
        }
        for (int ni = 0; ni < notes.size(); ni++) {
            if (un[ni]) continue;
            Note nt = notes.get(ni);
            if (!inLane(nt.x, nt.y, nt.size)) continue;
            if (hitTime(nt.x, nt.y, t) < t - 120) continue;          // もう通り過ぎた
            entries.add(new Entry(nextId++, nt, t));
        }
    }

    /** 判定の丸を通る流れの上にあるか（横にずれすぎていないか） */
    private boolean inLane(float x, float y, float size) {
        double sp = speed();
        if (sp < 1e-6) return false;
        double lat = Math.abs((jx - x) * vy - (jy - y) * vx) / sp;
        return lat <= Math.max(40, size * 0.8);
    }

    private long hitTime(float x, float y, long t) {
        double v2 = vx * vx + vy * vy;
        if (v2 < 1e-9) return Long.MAX_VALUE / 4;
        double s = ((jx - x) * vx + (jy - y) * vy) / v2;
        return t + Math.round(s);
    }

    long hitTime(Entry e) { return hitTime(e.x, e.y, e.t); }

    /**
     * いまから windowMs 先までに叩く予定を返す（返したものは予約済みになる）。
     * 密集しているときは、続きの音符もまとめる（区切りで遅れないように）。
     * @param offset タイミング調整（＋で遅く）
     * @param rollMs 連打の間隔
     */
    public List<Tap> plan(long now, long windowMs, int offset, int rollMs) {
        List<Tap> out = new ArrayList<>();
        if (!vKnown) return out;
        List<Entry> ready = new ArrayList<>();
        for (Entry e : entries) if (!e.sent && e.n >= 2) ready.add(e);
        Collections.sort(ready, (a, b) -> Long.compare(hitTime(a), hitTime(b)));

        long limit = now + windowMs;
        long last = Long.MIN_VALUE;
        long rollHead = Long.MAX_VALUE;
        for (Entry e : ready) {
            long at = hitTime(e) + offset;
            if (at > limit && !(last != Long.MIN_VALUE && at - last <= 80 && out.size() < 16)) break;
            if (at < now - 60) { e.sent = true; continue; }      // 間に合わない
            e.sent = true;
            if (e.kind == ROLL) {
                rollHead = Math.min(rollHead, at);
                continue;
            }
            // 同じ音符を二重に追いかけていたときは1回だけ
            boolean dup = false;
            for (Tap p : out) if (p.kind == e.kind && Math.abs(p.at - at) < 20) dup = true;
            if (dup) continue;
            out.add(new Tap(Math.max(now, at), e.kind));
            if (e.kind == DON) dons++; else kas++;
            last = at;
            limit = Math.max(limit, at);
        }
        // 連打：頭が判定の丸に来てから、終わりの見積もりまで
        if (rollHead != Long.MAX_VALUE) {
            rollNext = Math.max(rollNext, rollHead);
            rollUntil = Math.max(rollUntil, rollHead - offset + rollMs);
        } else if (rollNext != Long.MIN_VALUE && rollUntil < now - 300) {
            rollNext = Long.MIN_VALUE;                 // 連打は終わった（次の連打の頭が来るまで叩かない）
        }
        long rollEnd = Math.min(rollUntil + offset, now + windowMs);
        if (rollNext != Long.MIN_VALUE && rollNext <= rollEnd) {
            long tt = Math.max(rollNext, now);
            while (tt <= rollEnd && out.size() < 20) {
                boolean clash = false;
                for (Tap p : out) if (Math.abs(p.at - tt) < 25) clash = true;
                if (!clash) { out.add(new Tap(tt, DON)); rollTaps++; }
                tt += rollMs;
            }
            rollNext = tt;
        }
        Collections.sort(out, (a, b) -> Long.compare(a.at, b.at));
        return out;
    }
}
