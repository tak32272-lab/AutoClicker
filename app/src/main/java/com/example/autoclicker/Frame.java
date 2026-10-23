package com.example.autoclicker;

/** 作業解像度（画面の 1/f）に縮小した画面1枚 */
final class Frame {
    int w, h;          // 作業解像度の大きさ
    int[] argb;        // ARGB の画素
    float sx, sy;      // 画面ピクセル ÷ 作業ピクセル
    int f;             // 縮小倍率（登録画像の作り方と合わせる）
    long tsMs;         // 撮った時刻（System.nanoTime 基準のミリ秒）
    long seq;          // 何枚目か
    boolean inUse;     // 処理中（上書きしない）

    void ensure(int nw, int nh) {
        if (argb == null || argb.length != nw * nh) argb = new int[nw * nh];
        w = nw;
        h = nh;
    }

    static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** 画面の大きさから作業解像度の縮小倍率を決める（短い辺が約 360 になるように） */
    static int workFactor(int w, int h) {
        return Math.max(1, Math.round(Math.min(w, h) / 360f));
    }

    /** 画面を受け取る仕組み（高速モード／通常モード） */
    interface Source {
        /** afterSeq より新しい画面を待つ。timeoutMs 待っても来なければ最新のもの（なければ null） */
        Frame acquire(long afterSeq, long timeoutMs) throws InterruptedException;

        void release(Frame f);

        boolean alive();

        boolean fast();

        void close();
    }

    /** 3枚の画面を使い回す受け渡し場所（書く側1つ・読む側1つ） */
    static final class Exchange {
        private final Frame[] bufs = {new Frame(), new Frame(), new Frame()};
        private Frame latest;
        private long seq;
        private boolean closed;

        /** 書き込みに使える1枚（なければ null＝読む側が遅いので今回は捨てる） */
        synchronized Frame free() {
            for (Frame b : bufs) if (!b.inUse && b != latest) return b;
            return null;
        }

        synchronized void publish(Frame f) {
            f.seq = ++seq;
            latest = f;
            notifyAll();
        }

        synchronized void reset() {
            latest = null;
        }

        synchronized Frame acquire(long afterSeq, long timeoutMs) throws InterruptedException {
            long end = nowMs() + timeoutMs;
            while (!closed && (latest == null || latest.seq <= afterSeq)) {
                long left = end - nowMs();
                if (left <= 0) break;
                wait(left);
            }
            if (closed || latest == null) return null;
            latest.inUse = true;
            return latest;
        }

        synchronized void release(Frame f) {
            if (f != null) f.inUse = false;
        }

        synchronized void close() {
            closed = true;
            notifyAll();
        }

        synchronized boolean closed() {
            return closed;
        }
    }
}
