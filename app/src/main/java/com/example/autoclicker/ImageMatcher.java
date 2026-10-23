package com.example.autoclicker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 画像照合（テンプレートマッチング）。
 * Android の機能は使っていないので、PC でもそのままテストできる。
 *
 * 方式：グレースケールの正規化相互相関（ZNCC）。
 *  ・切り取った画像のふちの色を「背景」とみなし、背景部分は比べない（背景が変わっても見つかる）
 *  ・半分の解像度でおおまかに候補を探し、元の解像度で位置を細かく決める
 *  ・見つけた場所の色が登録画像と大きく違えば除外できる（色違いの敵を区別）
 */
public final class ImageMatcher {

    private ImageMatcher() { }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

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

        /** 半分の大きさにする */
        public Gray down2() {
            int nw = w / 2, nh = h / 2;
            Gray g = new Gray(nw, nh);
            for (int y = 0; y < nh; y++) {
                int r0 = 2 * y * w, r1 = r0 + w;
                for (int x = 0; x < nw; x++) {
                    int i = 2 * x;
                    g.p[y * nw + x] = (p[r0 + i] + p[r0 + i + 1] + p[r1 + i] + p[r1 + i + 1]) * 0.25f;
                }
            }
            return g;
        }

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

    // =====================================================================
    // 登録画像（テンプレート）
    // =====================================================================

    /** 比べる画素の一覧（背景を除いた画素だけ） */
    private static final class Prep {
        final int w, h, n;
        final int[] ox, oy;     // 比べる画素の位置
        final float[] z;        // 平均を引いた明るさ
        final double norm;

        Prep(int w, int h, int[] ox, int[] oy, float[] z, double norm) {
            this.w = w; this.h = h; this.n = z.length;
            this.ox = ox; this.oy = oy; this.z = z; this.norm = norm;
        }

        /** 画像の幅に合わせた「左上からのずれ」の表 */
        int[] offsets(int imgW) {
            int[] o = new int[n];
            for (int k = 0; k < n; k++) o[k] = oy[k] * imgW + ox[k];
            return o;
        }
    }

    private static Prep prep(Gray g, boolean[] mask) {
        int cnt = 0;
        for (int i = 0; i < g.p.length; i++) if (mask == null || mask[i]) cnt++;
        if (cnt < 9) return null;
        int[] ox = new int[cnt], oy = new int[cnt];
        float[] z = new float[cnt];
        double m = 0;
        int k = 0;
        for (int y = 0; y < g.h; y++)
            for (int x = 0; x < g.w; x++) {
                int i = y * g.w + x;
                if (mask != null && !mask[i]) continue;
                ox[k] = x; oy[k] = y; z[k] = g.p[i]; m += g.p[i]; k++;
            }
        m /= cnt;
        double ss = 0;
        for (int i = 0; i < cnt; i++) { z[i] -= (float) m; ss += z[i] * z[i]; }
        if (ss < cnt * 16.0) return null;          // ほぼ単色だと見分けられない
        return new Prep(g.w, g.h, ox, oy, z, Math.sqrt(ss));
    }

    /** 登録画像。縮小した明るさ・背景マスク・平均色を持つ */
    public static final class Template {
        public final Gray gray;
        public final boolean[] mask;     // true＝比べる画素（null＝全部）
        public final float[] color;      // 比べる画素の平均色 {r,g,b}
        final Prep fine, coarse;

        private Template(Gray gray, boolean[] mask, float[] color) {
            this.gray = gray;
            this.mask = mask;
            this.color = color;
            this.fine = prep(gray, mask);
            Prep c = null;
            if (gray.w >= 16 && gray.h >= 16) c = prep(gray.down2(), down2(mask, gray.w, gray.h));
            this.coarse = c;
        }

        /** 使える画像か（模様が少なすぎると false） */
        public boolean usable() { return fine != null; }

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
            return new Template(g, mask, col);
        }

        private final java.util.Map<Float, Template> scaledCache = new java.util.HashMap<>();

        /** 大きさを変えたもの（複数サイズ対応用。作ったものは覚えておく） */
        public Template scaled(float s) {
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
     * それに近い色の画素を「背景」とする。うまく分けられないときは null（全部比べる）。
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
        boolean[] m = new boolean[gw * gh];
        int fg = 0;
        for (int i = 0; i < m.length; i++) {
            float best = Float.MAX_VALUE;
            for (float[] r : ring) {
                float d = colorDist(col[i], r);
                if (d < best) { best = d; if (best < BG_DIST) break; }
            }
            m[i] = best >= BG_DIST;
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

    /** 見つかった場所（縮小した画面の座標） */
    public static final class Match {
        public final int x, y, w, h;
        public final float score;
        public final Template t;

        Match(int x, int y, Template t, float score) {
            this.x = x; this.y = y; this.t = t;
            this.w = t.gray.w; this.h = t.gray.h; this.score = score;
        }

        /** 見つけた場所の色と登録画像の色の違い（argb は元の解像度の画面） */
        public float colorDiff(int[] argb, int stride, int imgH, int f) {
            float[] c = maskedColor(argb, stride, imgH, x, y, f, t.mask, w, h);
            return colorDist(c, t.color);
        }
    }

    /** (x, y) に置いたときの似ている度合い（-1〜1） */
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

    /** 範囲内の全位置を調べ、まわりより高い点（山）を拾う */
    private static List<int[]> peaks(Gray img, Prep t, int x0, int y0, int x1, int y1,
                                     float thr, int limit, List<Float> scores) {
        List<int[]> pos = new ArrayList<>();
        if (x1 < x0 || y1 < y0) return pos;
        int mw = x1 - x0 + 1, mh = y1 - y0 + 1;
        float[] map = new float[mw * mh];
        int[] off = t.offsets(img.w);
        for (int y = 0; y < mh; y++) {
            int rowBase = (y0 + y) * img.w + x0;
            for (int x = 0; x < mw; x++) map[y * mw + x] = zncc(img.p, rowBase + x, t, off);
        }
        List<float[]> found = new ArrayList<>();
        for (int y = 0; y < mh; y++) {
            for (int x = 0; x < mw; x++) {
                float v = map[y * mw + x];
                if (v < thr) continue;
                boolean top = true;
                for (int dy = -1; dy <= 1 && top; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if ((dx | dy) == 0 || nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue;
                        if (map[ny * mw + nx] > v) { top = false; break; }
                    }
                if (top) found.add(new float[]{x0 + x, y0 + y, v});
            }
        }
        Collections.sort(found, (a, b) -> Float.compare(b[2], a[2]));
        for (int i = 0; i < found.size() && i < limit; i++) {
            pos.add(new int[]{(int) found.get(i)[0], (int) found.get(i)[1]});
            scores.add(found.get(i)[2]);
        }
        return pos;
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
     * img の中から登録画像 t と似ている場所を探す（img は Template と同じ倍率で縮小したもの）。
     * @param roi      探す範囲 {x0, y0, x1, y1}（img の座標。null なら全体）
     * @param minScore これ以上似ていれば「見つけた」（0〜1）
     * @param maxCount 最大いくつ返すか（点数の高い順）
     */
    public static List<Match> find(Gray img, Template t, int[] roi, float minScore, int maxCount) {
        List<Match> out = new ArrayList<>();
        if (t.fine == null) return out;
        int tw = t.gray.w, th = t.gray.h;
        int rx0 = 0, ry0 = 0, rx1 = img.w, ry1 = img.h;
        if (roi != null) {
            rx0 = clamp(roi[0], 0, img.w);
            ry0 = clamp(roi[1], 0, img.h);
            rx1 = clamp(roi[2], 0, img.w);
            ry1 = clamp(roi[3], 0, img.h);
        }
        if (rx1 - rx0 < tw || ry1 - ry0 < th) return out;

        List<Match> cand = new ArrayList<>();
        if (t.coarse != null) {
            // 1. 半分の解像度でおおまかに探す
            Gray ic = img.down2();
            float cmin = Math.max(0.3f, minScore - 0.25f);
            List<Float> sc = new ArrayList<>();
            List<int[]> coarse = peaks(ic, t.coarse, rx0 / 2, ry0 / 2,
                    Math.min(rx1 / 2, ic.w) - t.coarse.w, Math.min(ry1 / 2, ic.h) - t.coarse.h, cmin, 60, sc);
            // 2. 元の解像度で候補のまわり ±3 画素を細かく探す
            int[] off = t.fine.offsets(img.w);
            for (int[] c : coarse) {
                int bx = -1, by = -1;
                float bs = -2f;
                for (int y = c[1] * 2 - 3; y <= c[1] * 2 + 3; y++) {
                    for (int x = c[0] * 2 - 3; x <= c[0] * 2 + 3; x++) {
                        if (x < rx0 || y < ry0 || x + tw > rx1 || y + th > ry1) continue;
                        float s = zncc(img.p, y * img.w + x, t.fine, off);
                        if (s > bs) { bs = s; bx = x; by = y; }
                    }
                }
                if (bx >= 0 && bs >= minScore) cand.add(new Match(bx, by, t, bs));
            }
        } else {
            // 小さい画像は最初から細かく探す
            List<Float> sc = new ArrayList<>();
            List<int[]> ps = peaks(img, t.fine, rx0, ry0, rx1 - tw, ry1 - th, minScore, 200, sc);
            for (int i = 0; i < ps.size(); i++) cand.add(new Match(ps.get(i)[0], ps.get(i)[1], t, sc.get(i)));
        }
        return nms(cand, maxCount);
    }

    /** 大きさを変えた登録画像でも探す（scales 例：0.8, 1.0, 1.25） */
    public static List<Match> findMultiScale(Gray img, Template t, int[] roi, float minScore,
                                             int maxCount, float[] scales) {
        List<Match> all = new ArrayList<>();
        for (float s : scales) all.addAll(find(img, t.scaled(s), roi, minScore, maxCount));
        return nms(all, maxCount);
    }
}
