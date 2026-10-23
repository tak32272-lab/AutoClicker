package com.example.autoclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * 高速モード：画面共有（MediaProjection）で画面を受け取り続けるサービス。
 * 画面は作業解像度（1/f）に縮小した状態で受け取るので、1秒に数十回でも軽い。
 * 探していないときは受け取りを止めて電池を節約する。
 */
public class CaptureService extends Service {

    static final String TAG = "AC";
    static final String ACTION_STOP = "com.example.autoclicker.STOP_CAPTURE";
    private static final String CHANNEL = "capture";
    private static final int NOTIF_ID = 7;

    static volatile CaptureService instance;

    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler h;
    private DisplayManager dm;
    private final Frame.Exchange ex = new Frame.Exchange();

    private volatile boolean active;
    private volatile boolean stopped;
    private int realW, realH, dpi, f, vw, vh;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (projection != null) return START_NOT_STICKY;     // すでに動いている

        int code = intent.getIntExtra("code", 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("data", Intent.class);
        else data = intent.getParcelableExtra("data");
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = (data == null) ? null : mpm.getMediaProjection(code, data);
        } catch (Throwable e) {
            Log.w(TAG, "getMediaProjection failed", e);
            projection = null;
        }
        if (projection == null) {
            ClickerService.onCaptureDenied();
            stopSelf();
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("ac-capture", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        h = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped by system/user");
                stopped = true;
                ex.close();
                stopSelf();
            }
        }, h);
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        try {
            configure();
        } catch (Throwable e) {
            Log.w(TAG, "createVirtualDisplay failed", e);
            ClickerService.onCaptureDenied();
            stopSelf();
            return START_NOT_STICKY;
        }
        dm.registerDisplayListener(displayListener, h);
        instance = this;
        Log.i(TAG, "capture ready " + realW + "x" + realH + " -> " + vw + "x" + vh + " f=" + f);
        ClickerService.onCaptureReady();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "高速モード", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("オートクリッカー：高速モード")
                .setContentText("探すために画面を確認しています（タップで終了）")
                .setOngoing(true)
                .setContentIntent(stop)
                .addAction(new Notification.Action.Builder(null, "終了", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** 画面の大きさに合わせて受け取り口を作る（回転したときも呼ぶ） */
    private void configure() {
        DisplayMetrics m = new DisplayMetrics();
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(m);
        int w = m.widthPixels, hh = m.heightPixels;
        if (reader != null && w == realW && hh == realH) return;
        realW = w;
        realH = hh;
        dpi = m.densityDpi;
        f = Frame.workFactor(w, hh);
        vw = Math.max(2, Math.round(w / (float) f));
        vh = Math.max(2, Math.round(hh / (float) f));

        ImageReader old = reader;
        reader = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, h);
        if (vd == null) {
            vd = projection.createVirtualDisplay("ac-capture", vw, vh, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    active ? reader.getSurface() : null, null, h);
        } else {
            vd.resize(vw, vh, dpi);
            vd.setSurface(active ? reader.getSurface() : null);
        }
        ex.reset();
        if (old != null) old.close();
    }

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }

        @Override
        public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY || stopped || projection == null) return;
            try {
                configure();
            } catch (Throwable e) {
                Log.w(TAG, "reconfigure failed", e);
            }
        }
    };

    /** 新しい画面が来た（受け取り用スレッド） */
    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Throwable e) {
            return;
        }
        if (img == null) return;
        try {
            if (!active || r != reader) return;
            Frame fr = ex.free();
            if (fr == null) return;                 // 処理が追いついていないので、この1枚は捨てる
            Image.Plane p = img.getPlanes()[0];
            int w = img.getWidth(), hh = img.getHeight();
            int rowStride = p.getRowStride(), pixStride = p.getPixelStride();
            fr.ensure(w, hh);
            ByteBuffer bb = p.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
            int[] a = fr.argb;
            if (pixStride == 4 && rowStride % 4 == 0) {
                IntBuffer ib = bb.asIntBuffer();
                int rs = rowStride / 4;
                for (int y = 0; y < hh; y++) {
                    ib.position(y * rs);
                    ib.get(a, y * w, w);
                }
            } else {
                for (int y = 0; y < hh; y++)
                    for (int x = 0; x < w; x++) a[y * w + x] = bb.getInt(y * rowStride + x * pixStride);
            }
            // メモリ上は R,G,B,A の順 → ARGB の int に並べ替える
            int n = w * hh;
            for (int i = 0; i < n; i++) {
                int c = a[i];
                a[i] = 0xFF000000 | ((c << 16) & 0xFF0000) | (c & 0xFF00) | ((c >> 16) & 0xFF);
            }
            fr.sx = realW / (float) w;
            fr.sy = realH / (float) hh;
            fr.f = f;
            long now = Frame.nowMs();
            long ts = img.getTimestamp() / 1_000_000L;
            fr.tsMs = (ts > 0 && ts <= now + 5 && now - ts < 1000) ? ts : now;
            fr.arrMs = now;
            ex.publish(fr);
        } catch (Throwable e) {
            Log.w(TAG, "frame copy failed", e);
        } finally {
            img.close();
        }
    }

    /** 探している間だけ画面を受け取る */
    void setActive(final boolean on) {
        Handler hh = h;
        if (hh == null) return;
        hh.post(() -> {
            active = on;
            ex.reset();
            if (vd != null && reader != null) {
                try {
                    vd.setSurface(on ? reader.getSurface() : null);
                } catch (Throwable e) {
                    Log.w(TAG, "setSurface failed", e);
                }
            }
        });
    }

    boolean alive() {
        return !stopped && projection != null && !ex.closed();
    }

    Frame.Exchange exchange() {
        return ex;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        instance = null;
        ex.close();
        if (dm != null) {
            try { dm.unregisterDisplayListener(displayListener); } catch (Throwable ignored) { }
        }
        final VirtualDisplay v = vd;
        final ImageReader r = reader;
        final MediaProjection mp = projection;
        vd = null;
        reader = null;
        projection = null;
        Runnable release = () -> {
            try { if (v != null) v.release(); } catch (Throwable ignored) { }
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
            try { if (mp != null) mp.stop(); } catch (Throwable ignored) { }
        };
        if (h != null) {
            h.post(release);
            thread.quitSafely();
        } else {
            release.run();
        }
        stopForeground(true);
        ClickerService.onCaptureStopped();
        super.onDestroy();
    }

    /** 高速モード用の Frame.Source */
    static final class Src implements Frame.Source {
        private final CaptureService s;

        Src(CaptureService s) {
            this.s = s;
            s.setActive(true);
        }

        @Override
        public Frame acquire(long afterSeq, long timeoutMs) throws InterruptedException {
            return s.ex.acquire(afterSeq, timeoutMs);
        }

        @Override public void release(Frame f) { s.ex.release(f); }
        @Override public boolean alive() { return s.alive(); }
        @Override public boolean fast() { return true; }
        @Override public void close() { s.setActive(false); }
    }
}
