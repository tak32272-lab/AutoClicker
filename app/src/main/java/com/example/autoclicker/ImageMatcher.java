package com.example.autoclicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 画像照合（テンプレートマッチング）。
 * Android の機能は使っていないので、PC でもそのままテストできる。
 *
 * 方式：グレースケールの正規化相互相関（ZNCC）。
 *  ・切り取った画像のふちの色を「背景」とみなし、背景部分は比べない（背景が変わっても見つかる）
 *  ・画面と登録画像の両方を軽くぼかしてから比べる（縮小のしかたや位置の細かいずれに強くなる）
 *  ・1/4 → 1/2 → 等倍 の順に候補をしぼる。いちばん粗い段だけ全体を調べ、そこは画素を間引き、複数スレッドで並列に計算する
 *  ・見つけた場所の色が登録画像と大きく違えば除外できる（色違いの敵を区別）
 */
public final class ImageMatcher {

    private ImageMatcher() { }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // =====================================================================
    // 並列計算用のスレッド
    // =====================================================================

    static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static ExecutorService pool;

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(THREADS, r -> {
                Thread t = new Thread(r, "ac-match");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + 2);
                return t;
            });
        }
        return pool;
    }

    /** 0〜n-1 の行を分けて並列に処理する（呼び出したスレッドも手伝う） */
    private static void parallelRows(int n, final RowTask task) {
        int parts = Math.min(THREADS + 1, Math.max(1, n / 8));
        if (parts <= 1) {
            task.run(0, n);
            return;
        }
        final CountDownLatch latch = new CountDownLatch(parts - 1);
        final Throwable[] err = new Throwable[1];
        int step = (n + parts - 1) / parts;
        ExecutorService ex = pool();
        for (int i = 1; i < parts; i++) {
            final int a = i * step, b = Math.min(n, a + step);
            ex.execute(() -> {
                try {
                    if (a < b) task.run(a, b);
                } catch (Throwable t) {
                    err[0] = t;
                } finally {
                    latch.countDown();
                }
            });
        }
        task.run(0, Math.min(n, step));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (err[0] != null) throw new RuntimeException(err[0]);
    }

    private interface RowTask { void run(int from, int to); }

    // =====================================================================
    // グレースケール画像
    // =====================================================================

    /** グレースケール画像（値は 0〜255） */
    public static final class Gray {
        public final int w, h;
        public final float[] p;

        public Gray(int w, int h) {
            this.w = Math.max(0, w);
            this.h = Math.max(0, h);
            this.p = new float[this.w * this.h];
        }

        static Gray ensure(Gray g, int w, int h) {
            return (g != null && g.w == w && g.h == h) ? g : new Gray(w, h);
        }

        /** ARGB 画像の一部 (x, y, rw, rh) を f×f 画素ずつ平均して縮小し、グレースケールにする */
        public static Gray fromArgb(int[] argb, int stride, int x, int y, int rw, int rh, int f) {
            int gw = rw / f, gh = rh / f;
            Gray g = new Gray(gw, gh);
            float inv = 1f / (f * f);
            for (int gy = 0; gy < gh; gy++) {
                int by = y + gy * f;
                for (int gx = 0; gx < gw; gx++) {
                    int bx = x + gx * f;
                    int sum = 0;
                    for (int yy = 0; yy < f; yy++) {
                        int row = (by + yy) * stride + bx;
                        for (int xx = 0; xx < f; xx++) {
                            int c = argb[row + xx];
                            sum += (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
                        }
                    }
                    g.p[gy * gw + gx] = sum * inv;
                }
            }
            return g;
        }

        /** 等倍の ARGB 画像 (w×h) をそのままグレースケールにする（dst は使い回し用、null 可） */
        public static Gray fromArgb1(int[] argb, int w, int h, Gray dst) {
            Gray g = ensure(dst, w, h);
            float[] p = g.p;
            int n = w * h;
            for (int i = 0; i < n; i++) {
                int c = argb[i];
                p[i] = (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
            }
            return g;
        }

        /** 3×3 のぼかし（[1 2 1] を横と縦に）。tmp・dst は使い回し用（null 可、this は不可） */
        public Gray blur(Gray tmp, Gray dst) {
            final Gray t = ensure(tmp, w, h), d = ensure(dst, w, h);
            if (w < 3 || h < 3) {
                System.arraycopy(p, 0, d.p, 0, p.length);
                return d;
            }
            final float[] s = p, tp = t.p, dp = d.p;
            for (int y = 0; y < h; y++) {
                int r = y * w;
                tp[r] = (3 * s[r] + s[r + 1]) * 0.25f;
                for (int x = 1; x < w - 1; x++) tp[r + x] = (s[r + x - 1] + 2 * s[r + x] + s[r + x + 1]) * 0.25f;
                tp[r + w - 1] = (s[r + w - 2] + 3 * s[r + w - 1]) * 0.25f;
            }
            for (int y = 0; y < h; y++) {
                int r = y * w, ru = (y > 0 ? y - 1 : 0) * w, rd = (y < h - 1 ? y + 1 : y) * w;
                for (int x = 0; x < w; x++) dp[r + x] = (tp[ru + x] + 2 * tp[r + x] + tp[rd + x]) * 0.25f;
            }
            return d;
        }

        /** 半分の大きさにする（dst は使い回し用、null 可） */
        public Gray down2(Gray dst) {
            int nw = w / 2, nh = h / 2;
            Gray g = ensure(dst, nw, nh);
            for (int y = 0; y < nh; y++) {
                int r0 = 2 * y * w, r1 = r0 + w;
                for (int x = 0; x < nw; x++) {
                    int i = 2 * x;
                    g.p[y * nw + x] = (p[r0 + i] + p[r0 + i + 1] + p[r1 + i] + p[r1 + i + 1]) * 0.25f;
                }
            }
            return g;
        }

        public Gray down2() { return down2(null); }

        /** 指定サイズに拡大・縮小（双線形） */
        public Gray resize(int nw, int nh) {
            nw = Math.max(1, nw);
            nh = Math.max(1, nh);
            Gray g = new Gray(nw, nh);
            float sx = (float) w / nw, sy = (float) h / nh;
            for (int y = 0; y < nh; y++) {
                float fy = (y + 0.5f) * sy - 0.5f;
                int y0 = clamp((int) Math.floor(fy), 0, h - 1), y1 = Math.min(y0 + 1, h - 1);
                float dy = Math.max(0f, Math.min(1f, fy - y0));
                for (int x = 0; x < nw; x++) {
                    float fx = (x + 0.5f) * sx - 0.5f;
                    int x0 = clamp((int) Math.floor(fx), 0, w - 1), x1 = Math.min(x0 + 1, w - 1);
                    float dx = Math.max(0f, Math.min(1f, fx - x0));
                    float a = p[y0 * w + x0] * (1 - dx) + p[y0 * w + x1] * dx;
                    float b = p[y1 * w + x0] * (1 - dx) + p[y1 * w + x1] * dx;
                    g.p[y * nw + x] = a * (1 - dy) + b * dy;
                }
            }
            return g;
        }

        /** 明るさのばらつき（標準偏差）。小さいほど単色に近い */
        public float std() {
            if (p.length == 0) return 0f;
            double s = 0, s2 = 0;
            for (float v : p) { s += v; s2 += v * v; }
            double m = s / p.length;
            return (float) Math.sqrt(Math.max(0, s2 / p.length - m * m));
        }
    }

    /** 1フレーム分の画面（等倍・1/2・1/4）。毎フレーム作り直さず使い回す */
    public static final class Pyramid {
        final Gray[] lv = new Gray[3];
        private Gray raw, tmp;

        /** ARGB（作業解像度）から作る */
        public void build(int[] argb, int w, int h) {
            raw = Gray.fromArgb1(argb, w, h, raw);
            lv[0] = raw.blur(tmp = Gray.ensure(tmp, w, h), lv[0]);
            lv[1] = lv[0].down2(lv[1]);
            lv[2] = lv[1].down2(lv[2]);
        }

        public int w() { return lv[0] == null ? 0 : lv[0].w; }
        public int h() { return lv[0] == null ? 0 : lv[0].h; }
    }

    // =====================================================================
    // 登録画像（テンプレート）
    // =====================================================================

    /** 粗い段の画像はこれより小さくしない（画素） */
    static final int MIN_SIDE = 8;
    /** いちばん粗い段で全体を調べるときに使う画素の最大数 */
    static final int MAX_TOP_POINTS = 160;

    /** 比べる画素の一覧（背景を除いた画素だけ） */
    static final class Prep {
        final int w, h, n;
        final int[] ox, oy;     // 比べる画素の位置
        final float[] v;        // 元の明るさ
        final float[] z;        // 平均を引いた明るさ
        final double norm;
        private int offW = -1;
        private int[] off;

        Prep(int w, int h, int[] ox, int[] oy, float[] v) {
            this.w = w; this.h = h; this.n = v.length;
            this.ox = ox; this.oy = oy; this.v = v;
            double m = 0;
            for (float a : v) m += a;
            m /= Math.max(1, n);
            z = new float[n];
            double ss = 0;
            for (int i = 0; i < n; i++) { z[i] = (float) (v[i] - m); ss += z[i] * z[i]; }
            norm = Math.sqrt(ss);
        }

        boolean flat() { return norm * norm < n * 16.0; }

        /** 画像の幅に合わせた「左上からのずれ」の表（最後に使った幅のものを覚えておく） */
        synchronized int[] offsets(int imgW) {
            if (imgW != offW) {
                int[] o = new int[n];
                for (int k = 0; k < n; k++) o[k] = oy[k] * imgW + ox[k];
                off = o;
                offW = imgW;
            }
            return off;
        }

        /** 画素を間引いたもの（最大 k 個、全体にまんべんなく） */
        Prep sample(int k) {
            if (n <= k) return this;
            int[] sx = new int[k], sy = new int[k];
            float[] sv = new float[k];
            for (int i = 0; i < k; i++) {
                int j = (int) ((long) i * n / k);
                sx[i] = ox[j]; sy[i] = oy[j]; sv[i] = v[j];
            }
            Prep p = new Prep(w, h, sx, sy, sv);
            return p.flat() ? this : p;
        }
    }

    private static Prep prep(Gray g, boolean[] mask) {
        int cnt = 0;
        for (int i = 0; i < g.p.length; i++) if (mask == null || mask[i]) cnt++;
        if (cnt < 9) return null;
        int[] ox = new int[cnt], oy = new int[cnt];
        float[] v = new float[cnt];
        int k = 0;
        for (int y = 0; y < g.h; y++)
            for (int x = 0; x < g.w; x++) {
                int i = y * g.w + x;
                if (mask != null && !mask[i]) continue;
                ox[k] = x; oy[k] = y; v[k] = g.p[i]; k++;
            }
        Prep p = new Prep(g.w, g.h, ox, oy, v);
        return p.flat() ? null : p;              // ほぼ単色だと見分けられない
    }

    /** 登録画像。縮小した明るさ・背景マスク・平均色を持つ */
    public static final class Template {
        public final Gray gray;          // ぼかした作業解像度の明るさ
        public final boolean[] mask;     // true＝比べる画素（null＝全部）
        public final float[] color;      // 比べる画素の平均色 {r,g,b}
        final Prep[] lv = new Prep[3];   // 0:等倍 1:1/2 2:1/4
        final int topLevel;              // 全体を調べる段
        final Prep top;                  // その段の、間引いた画素
        public final float cx, cy;       // 中身（背景以外）の中心（左上からの位置、作業解像度）

        private Template(Gray gray, boolean[] mask, float[] color) {
            this.gray = gray;
            this.mask = mask;
            this.color = color;
            float sx = 0, sy = 0;
            int cnt = 0;
            if (mask != null) {
                for (int y = 0; y < gray.h; y++)
                    for (int x = 0; x < gray.w; x++)
                        if (mask[y * gray.w + x]) { sx += x + 0.5f; sy += y + 0.5f; cnt++; }
            }
            cx = cnt > 0 ? sx / cnt : gray.w / 2f;
            cy = cnt > 0 ? sy / cnt : gray.h / 2f;
            lv[0] = prep(gray, mask);
            int topL = 0;
            Gray g = gray;
            boolean[] m = mask;
            for (int l = 1; l <= 2 && lv[0] != null; l++) {
                if (g.w / 2 < MIN_SIDE || g.h / 2 < MIN_SIDE) break;
                m = down2(m, g.w, g.h);
                g = g.down2();
                Prep p = prep(g, m);
                if (p == null) break;
                lv[l] = p;
                topL = l;
            }
            topLevel = topL;
            top = lv[topL] == null ? null : lv[topL].sample(MAX_TOP_POINTS);
        }

        /** 使える画像か（模様が少なすぎると false） */
        public boolean usable() { return lv[0] != null; }

        /** 比べる画素の割合（背景を除けた度合いの目安） */
        public float maskRatio() {
            if (mask == null) return 1f;
            int c = 0;
            for (boolean b : mask) if (b) c++;
            return c / (float) mask.length;
        }

        /**
         * ARGB の切り取り画像から作る。
         * @param f        縮小倍率（画面と同じ値にする）
         * @param autoMask ふちの色に近い画素を背景として除く
         */
        public static Template create(int[] argb, int w, int h, int f, boolean autoMask) {
            Gray g = Gray.fromArgb(argb, w, 0, 0, w, h, f);
            boolean[] mask = autoMask ? autoMask(argb, w, g.w, g.h, f) : null;
            float[] col = maskedColor(argb, w, h, 0, 0, f, mask, g.w, g.h);
            return new Template(g.blur(null, null), mask, col);
        }

        private final Map<Float, Template> scaledCache = new HashMap<>();

        /** 大きさを変えたもの（複数サイズ対応用。作ったものは覚えておく） */
        public synchronized Template scaled(float s) {
            if (s == 1f) return this;
            Template c = scaledCache.get(s);
            if (c == null) { c = makeScaled(s); scaledCache.put(s, c); }
            return c;
        }

        private Template makeScaled(float s) {
            int nw = Math.max(3, Math.round(gray.w * s)), nh = Math.max(3, Math.round(gray.h * s));
            boolean[] m = null;
            if (mask != null) {
                m = new boolean[nw * nh];
                for (int y = 0; y < nh; y++)
                    for (int x = 0; x < nw; x++) {
                        int sx = clamp((int) ((x + 0.5f) * gray.w / nw), 0, gray.w - 1);
                        int sy = clamp((int) ((y + 0.5f) * gray.h / nh), 0, gray.h - 1);
                        m[y * nw + x] = mask[sy * gray.w + sx];
                    }
            }
            return new Template(gray.resize(nw, nh), m, color);
        }
    }

    private static boolean[] down2(boolean[] m, int w, int h) {
        if (m == null) return null;
        int nw = w / 2, nh = h / 2;
        boolean[] o = new boolean[nw * nh];
        for (int y = 0; y < nh; y++)
            for (int x = 0; x < nw; x++) {
                int c = 0, i = 2 * y * w + 2 * x;
                if (m[i]) c++;
                if (m[i + 1]) c++;
                if (m[i + w]) c++;
                if (m[i + w + 1]) c++;
                o[y * nw + x] = c >= 2;
            }
        return o;
    }

    /** f×f ブロックの平均色 */
    private static void blockColor(int[] argb, int stride, int bx, int by, int f, float[] out) {
        int r = 0, g = 0, b = 0;
        for (int yy = 0; yy < f; yy++) {
            int row = (by + yy) * stride + bx;
            for (int xx = 0; xx < f; xx++) {
                int c = argb[row + xx];
                r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
        }
        float inv = 1f / (f * f);
        out[0] = r * inv; out[1] = g * inv; out[2] = b * inv;
    }

    /**
     * 背景マスクを作る。切り取ったふち（外周2画素）の色を背景の見本にして、
     * それに近い色で、しかも外側からつながっている画素を「背景」とする
     * （敵の体が背景と似た色でも、輪郭で囲まれていれば敵の一部として残る）。
     * うまく分けられないときは null（全部比べる）。
     */
    static boolean[] autoMask(int[] argb, int stride, int gw, int gh, int f) {
        if (gw < 8 || gh < 8) return null;
        float[][] col = new float[gw * gh][3];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                blockColor(argb, stride, x * f, y * f, f, col[y * gw + x]);
        List<float[]> ring = new ArrayList<>();
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                if (x < 2 || y < 2 || x >= gw - 2 || y >= gh - 2) ring.add(col[y * gw + x]);
        int n = gw * gh;
        boolean[] bgLike = new boolean[n];
        for (int i = 0; i < n; i++) {
            float best = Float.MAX_VALUE;
            for (float[] r : ring) {
                float d = colorDist(col[i], r);
                if (d < best) { best = d; if (best < BG_DIST) break; }
            }
            bgLike[i] = best < BG_DIST;
        }
        // 外周から、背景に似た色の画素をたどる（上下左右のつながり）
        boolean[] bg = new boolean[n];
        int[] queue = new int[n];
        int qh = 0, qt = 0;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) {
                if (x != 0 && y != 0 && x != gw - 1 && y != gh - 1) continue;
                int i = y * gw + x;
                if (bgLike[i] && !bg[i]) { bg[i] = true; queue[qt++] = i; }
            }
        while (qh < qt) {
            int i = queue[qh++], x = i % gw, y = i / gw;
            if (x > 0 && bgLike[i - 1] && !bg[i - 1]) { bg[i - 1] = true; queue[qt++] = i - 1; }
            if (x < gw - 1 && bgLike[i + 1] && !bg[i + 1]) { bg[i + 1] = true; queue[qt++] = i + 1; }
            if (y > 0 && bgLike[i - gw] && !bg[i - gw]) { bg[i - gw] = true; queue[qt++] = i - gw; }
            if (y < gh - 1 && bgLike[i + gw] && !bg[i + gw]) { bg[i + gw] = true; queue[qt++] = i + gw; }
        }
        boolean[] m = new boolean[n];
        int fg = 0;
        for (int i = 0; i < n; i++) {
            m[i] = !bg[i];
            if (m[i]) fg++;
        }
        // 孤立した点を消す（まわり8画素のうち2つ未満しか前景がない点）
        boolean[] c = m.clone();
        for (int y = 1; y < gh - 1; y++)
            for (int x = 1; x < gw - 1; x++) {
                int i = y * gw + x;
                if (!m[i]) continue;
                int nb = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        if ((dx | dy) != 0 && m[i + dy * gw + dx]) nb++;
                if (nb < 2) { c[i] = false; fg--; }
            }
        float ratio = fg / (float) m.length;
        if (ratio < 0.12f || ratio > 0.92f) return null;   // 分けられなかった
        return c;
    }

    /** 背景とみなす色の近さ */
    static final float BG_DIST = 36f;

    /**
     * (x0, y0)（縮小座標）から mw×mh の範囲で、マスクの画素の平均色を求める。
     * マスクがないときは中央 6 割の平均。
     */
    static float[] maskedColor(int[] argb, int stride, int imgH, int x0, int y0, int f,
                               boolean[] mask, int mw, int mh) {
        double r = 0, g = 0, b = 0;
        int n = 0;
        float[] tmp = new float[3];
        int maxX = stride / f, maxY = imgH / f;
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                if (mask != null) {
                    if (!mask[y * mw + x]) continue;
                } else if (x < mw / 5 || x >= mw - mw / 5 || y < mh / 5 || y >= mh - mh / 5) {
                    continue;
                }
                int gx = x0 + x, gy = y0 + y;
                if (gx < 0 || gy < 0 || gx >= maxX || gy >= maxY) continue;
                blockColor(argb, stride, gx * f, gy * f, f, tmp);
                r += tmp[0]; g += tmp[1]; b += tmp[2];
                n++;
            }
        }
        if (n == 0) return new float[]{0, 0, 0};
        return new float[]{(float) (r / n), (float) (g / n), (float) (b / n)};
    }

    /** 2つの色の違い（0〜441） */
    public static float colorDist(float[] a, float[] b) {
        float dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // =====================================================================
    // 探す
    // =====================================================================

    /** 見つかった場所（作業解像度の画面の座標。x,y は左上） */
    public static final class Match {
        public final int x, y, w, h;
        public final float score;
        public final Template t;

        Match(int x, int y, Template t, float score) {
            this.x = x; this.y = y; this.t = t;
            this.w = t.gray.w; this.h = t.gray.h; this.score = score;
        }

        /** 見つけた場所の色と登録画像の色の違い（argb は作業解像度の画面） */
        public float colorDiff(int[] argb, int stride, int imgH) {
            float[] c = maskedColor(argb, stride, imgH, x, y, 1, t.mask, w, h);
            return colorDist(c, t.color);
        }
    }

    /** base に置いたときの似ている度合い（-1〜1） */
    private static float zncc(float[] ip, int base, Prep t, int[] off) {
        int n = t.n;
        float[] z = t.z;
        double s = 0, s2 = 0, a = 0;
        for (int k = 0; k < n; k++) {
            float v = ip[base + off[k]];
            s += v;
            s2 += v * v;
            a += v * z[k];
        }
        double var = s2 - s * s / n;
        if (var < n * 4.0) return 0f;          // 単色の場所は無視
        return (float) (a / (Math.sqrt(var) * t.norm));
    }

    /** 範囲内の全位置を調べ、まわりより高い点（山）を点数の高い順に返す {x, y, score} */
    private static List<float[]> peaks(final Gray img, final Prep t, final int x0, final int y0,
                                       int x1, int y1, float thr, int limit) {
        List<float[]> found = new ArrayList<>();
        if (x1 < x0 || y1 < y0) return found;
        final int mw = x1 - x0 + 1, mh = y1 - y0 + 1;
        final float[] map = new float[mw * mh];
        final int[] off = t.offsets(img.w);
        parallelRows(mh, (from, to) -> {
            for (int y = from; y < to; y++) {
                int rowBase = (y0 + y) * img.w + x0, mr = y * mw;
                for (int x = 0; x < mw; x++) map[mr + x] = zncc(img.p, rowBase + x, t, off);
            }
        });
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                float v = map[y * mw + x];
                if (v < thr) continue;
                boolean top = true;
                for (int dy = -1; dy <= 1 && top; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if ((dx | dy) == 0 || nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue;
                        float o = map[ny * mw + nx];
                        // 同じ値が並ぶときは左上のものだけを山にする
                        if (o > v || (o == v && (dy < 0 || (dy == 0 && dx < 0)))) { top = false; break; }
                    }
                if (top) found.add(new float[]{x0 + x, y0 + y, v});
            }
        }
        Collections.sort(found, (a, b) -> Float.compare(b[2], a[2]));
        if (found.size() > limit) found = new ArrayList<>(found.subList(0, limit));
        return found;
    }

    private static float iou(Match a, Match b) {
        int ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
        int iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
        float inter = ix * iy;
        return inter / (a.w * a.h + b.w * b.h - inter);
    }

    /** 重なっている結果は点数の高い方だけ残す */
    private static List<Match> nms(List<Match> in, int maxCount) {
        List<Match> sorted = new ArrayList<>(in);
        Collections.sort(sorted, (a, b) -> Float.compare(b.score, a.score));
        List<Match> keep = new ArrayList<>();
        for (Match m : sorted) {
            boolean ok = true;
            for (Match k : keep) if (iou(m, k) > 0.3f) { ok = false; break; }
            if (ok) keep.add(m);
            if (keep.size() >= maxCount) break;
        }
        return keep;
    }

    /**
     * 粗い段 L で見つけた候補 (cx, cy) を、細かい段へ順に位置を詰めながら確かめる。
     * L が 0 のとき（間引いた画素で調べた場合）は、全部の画素で ±1 画素を確かめ直す。
     * @return {x, y, score}（等倍の座標）。範囲外なら null
     */
    private static float[] refine(Pyramid img, Template t, int cx, int cy, int L, int[] roi) {
        float bs = -2f;
        for (int l = (L == 0 ? 0 : L - 1); l >= 0; l--) {
            Prep p = t.lv[l];
            Gray g = img.lv[l];
            int r, bx, by;
            if (l == L) { r = 1; bx = cx; by = cy; }
            else { r = 2; bx = cx * 2; by = cy * 2; }
            int rx0 = roi[0] >> l, ry0 = roi[1] >> l;
            int rx1 = Math.min(g.w, roi[2] >> l) - p.w, ry1 = Math.min(g.h, roi[3] >> l) - p.h;
            int[] off = p.offsets(g.w);
            int nx = -1, ny = -1;
            bs = -2f;
            for (int y = by - r; y <= by + r; y++) {
                if (y < ry0 || y > ry1) continue;
                for (int x = bx - r; x <= bx + r; x++) {
                    if (x < rx0 || x > rx1) continue;
                    float s = zncc(g.p, y * g.w + x, p, off);
                    if (s > bs) { bs = s; nx = x; ny = y; }
                }
            }
            if (nx < 0) return null;
            cx = nx;
            cy = ny;
        }
        return new float[]{cx, cy, bs};
    }

    /**
     * 画面 img の中から登録画像 t と似ている場所を探す（img は Template と同じ倍率のもの）。
     * @param roi      探す範囲 {x0, y0, x1, y1}（作業解像度の座標。null なら全体）
     * @param minScore これ以上似ていれば「見つけた」（0〜1）
     * @param maxCount 最大いくつ返すか（点数の高い順）
     */
    public static List<Match> find(Pyramid img, Template t, int[] roi, float minScore, int maxCount) {
        List<Match> out = new ArrayList<>();
        if (t.lv[0] == null || t.top == null || img.lv[0] == null) return out;
        Gray g0 = img.lv[0];
        int[] r = {0, 0, g0.w, g0.h};
        if (roi != null) {
            r[0] = clamp(roi[0], 0, g0.w);
            r[1] = clamp(roi[1], 0, g0.h);
            r[2] = clamp(roi[2], 0, g0.w);
            r[3] = clamp(roi[3], 0, g0.h);
        }
        if (r[2] - r[0] < t.gray.w || r[3] - r[1] < t.gray.h) return out;

        int L = t.topLevel;
        Gray gl = img.lv[L];
        Prep tp = t.top;
        boolean sampled = tp != t.lv[L];
        float cthr = (L == 0 && !sampled) ? minScore : Math.max(0.2f, minScore - 0.3f);
        int x0 = r[0] >> L, y0 = r[1] >> L;
        int x1 = Math.min(gl.w, r[2] >> L) - tp.w, y1 = Math.min(gl.h, r[3] >> L) - tp.h;
        List<float[]> cand = peaks(gl, tp, x0, y0, x1, y1, cthr, L == 0 && !sampled ? 200 : 40);

        List<Match> ms = new ArrayList<>();
        for (float[] c : cand) {
            float[] f;
            if (L == 0 && !sampled) f = c;
            else f = refine(img, t, (int) c[0], (int) c[1], L, r);
            if (f != null && f[2] >= minScore) ms.add(new Match((int) f[0], (int) f[1], t, f[2]));
        }
        return nms(ms, maxCount);
    }

    /** 大きさを変えた登録画像でも探す（scales 例：0.8, 1.0, 1.25） */
    public static List<Match> findMultiScale(Pyramid img, Template t, int[] roi, float minScore,
                                             int maxCount, float[] scales) {
        List<Match> all = new ArrayList<>();
        for (float s : scales) all.addAll(find(img, t.scaled(s), roi, minScore, maxCount));
        return nms(all, maxCount);
    }
}
