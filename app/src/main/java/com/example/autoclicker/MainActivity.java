package com.example.autoclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 設定画面：サービスの有効化、再生回数・速度、画像探索の設定と登録画像の一覧 */
public class MainActivity extends Activity {

    private SharedPreferences sp;
    private TextView stateTv, speedTv, thrTv, coolTv, roiTv, transTv, tapTv;
    private LinearLayout listBox;
    private List<TemplateStore.Tpl> tpls;

    private interface IntCb { void on(int v); }
    private interface StrCb { void on(String s); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView tv(String text, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private TextView header(String text) {
        TextView t = tv(text, 18);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(24), 0, dp(4));
        return t;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** 小さいボタン（Android 標準の最小幅 88dp を外す） */
    private Button small(String text, View.OnClickListener l) {
        Button b = btn(text, l);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setTextSize(13);
        return b;
    }

    private SeekBar seek(int max, int progress, final IntCb cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(Math.max(0, Math.min(max, progress)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) { cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        return s;
    }

    private CheckBox check(String text, final String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(15);
        c.setChecked(sp.getBoolean(key, def));
        c.setOnCheckedChangeListener((b, on) -> sp.edit().putBoolean(key, on).apply());
        return c;
    }

    private static TextWatcher watcher(final StrCb cb) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { cb.on(s.toString()); }
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("ac", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 3);
        sv.addView(root);

        TextView title = tv("オートクリッカー", 22);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        stateTv = tv("", 15);
        root.addView(stateTv);
        root.addView(btn("① ユーザー補助の設定を開く",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(btn("② ⏯️パネルを表示", v -> {
            if (ClickerService.instance != null) {
                ClickerService.instance.showPanel();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, "先にユーザー補助で「オートクリッカー」をオンにしてください", Toast.LENGTH_LONG).show();
            }
        }));

        transTv = tv("", 15);
        root.addView(transTv);
        root.addView(seek(80, sp.getInt("trans", 50), p -> {          // 0〜80%
            sp.edit().putInt("trans", p).apply();
            showLabels();
            refreshPanel();                                             // 動かすとすぐパネルに反映
        }));

        // ---------- 記録・再生 ----------
        root.addView(header("タップの記録・再生"));
        root.addView(tv("再生回数（1〜99999）", 15));
        EditText count = new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setText(String.valueOf(sp.getInt("count", 100)));
        count.addTextChangedListener(watcher(s -> {
            int v;
            try { v = Integer.parseInt(s.trim()); } catch (Exception e) { return; }
            sp.edit().putInt("count", Math.max(1, Math.min(99999, v))).apply();
            refreshPanel();
        }));
        root.addView(count);

        speedTv = tv("", 15);
        root.addView(speedTv);
        root.addView(seek(99, sp.getInt("speed", 10) - 1, p -> {      // 0〜99 → 速度1〜100
            sp.edit().putInt("speed", p + 1).apply();
            showLabels();
            refreshPanel();
        }));

        root.addView(tv("使い方\n"
                + "・⏯️で記録開始 → ほかのアプリで好きな場所をタップ → ⏯️で記録終了\n"
                + "・もう一度⏯️で再生（再生中に⏯️で中断）\n"
                + "・⏯️長押し：記録し直し　🗑：記録を消去　✕：パネルを隠す\n"
                + "・パネルは文字の部分を指で動かせます。再生中は記録した場所に重ねないでください", 14));

        // ---------- 画像探索 ----------
        root.addView(header("画像を見つけてタップ（Android 11 以上）"));
        root.addView(tv("1. ゲームを開き、パネルの「📷 登録」で、モンスターなどを指でなぞって囲みます（周りに少し余白をつけるのがコツ）\n"
                + "2. 「🔍 探す」を押すと、登録した画像が画面に出たとき自動でタップします（もう一度押すと停止）\n"
                + "3. 「🔲 範囲」で、探す場所をしぼれます（例：キャラクターの周りだけ）\n"
                + "・切り取った画像のふちの色は「背景」として無視するので、背景が変わっても見つかります", 14));

        root.addView(header("反応の速さ"));
        root.addView(check("⚡ 高速モード（画面共有を使って、1秒に数十回確認）", "fast", true));
        root.addView(tv("「🔍 探す」を押したとき、最初の1回だけ「画面全体を共有」の確認が出ます。「今すぐ開始」を押してください。"
                + "共有中は通知とステータスバーに表示が出ます（パネルの✕か通知の「終了」で終わります）。"
                + "オフのときは約0.34秒に1回の確認です（Android の制限）。", 13));
        root.addView(check("🎯 動く敵は移動先を予測してタップする", "predict", true));

        tapTv = tv("", 15);
        root.addView(tapTv);
        root.addView(seek(29, (sp.getInt("tapMs", 40) - 10) / 5, p -> {   // 10〜155ms（5ms刻み）
            sp.edit().putInt("tapMs", 10 + p * 5).apply();
            showLabels();
        }));

        thrTv = tv("", 15);
        root.addView(thrTv);
        root.addView(seek(39, sp.getInt("thr", 80) - 60, p -> {       // 60〜99%
            sp.edit().putInt("thr", p + 60).apply();
            showLabels();
        }));

        coolTv = tv("", 15);
        root.addView(coolTv);
        root.addView(seek(50, sp.getInt("cool", 3), p -> {             // 0〜5.0秒
            sp.edit().putInt("cool", p).apply();
            showLabels();
        }));

        root.addView(check("色の違いも区別する（色違いの敵をタップしない）", "color", true));
        root.addView(check("大きさが変わっても探す（約3倍遅くなります）", "multi", false));
        root.addView(check("同じ画像が複数あれば全部タップする", "tapAll", false));

        roiTv = tv("", 15);
        root.addView(roiTv);
        root.addView(btn("探す範囲を全画面に戻す", v -> {
            sp.edit().putString("roi", "").apply();
            showLabels();
        }));

        root.addView(header("登録した画像（上ほど優先）"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        root.addView(tv("※ Redmi（MIUI）で①の画面からオンにできないとき：\n"
                + "設定 → アプリ → アプリを管理 → オートクリッカー → 右上の︙ →「制限付き設定を許可」をオンにしてから、もう一度①を試してください。\n"
                + "※ アップデート後に画像の機能が動かないときは、ユーザー補助を一度オフ→オンにしてください。", 13));

        setContentView(sv);

        // 高速モード中の通知（「終了」ボタン）を出せるように、最初の1回だけ許可を求める
        if (Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("askedNotif", false)
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            sp.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        stateTv.setText(ClickerService.instance != null
                ? "✅ サービス：オン"
                : "⚠️ サービス：オフ（①からオンにしてください）");
        showLabels();
        renderList();
    }

    private void showLabels() {
        transTv.setText("⏯️パネルの透明度 " + sp.getInt("trans", 50) + "%（0%＝透けない／80%＝かなり透ける）");
        int s = sp.getInt("speed", 10);
        speedTv.setText("速度 " + s + "（×" + String.format(Locale.US, "%.1f", s / 10f) + "）");
        thrTv.setText("一致度 " + sp.getInt("thr", 80) + "%（高いほど厳しく、低いほど見つけやすい）");
        int cool = sp.getInt("cool", 3);
        coolTv.setText("同じ敵をもう一度タップするまでの間隔 " + String.format(Locale.US, "%.1f", cool / 10f) + "秒"
                + (cool == 0 ? "（連打）" : "") + "\n（ほかの敵は待たずにすぐタップします）");
        tapTv.setText("タップの長さ " + sp.getInt("tapMs", 40) + "ミリ秒（ゲームが反応しないときは長く）");
        roiTv.setText(sp.getString("roi", "").isEmpty()
                ? "探す範囲：全画面（パネルの「🔲 範囲」で変更）"
                : "探す範囲：指定あり（パネルの「🔲 範囲」で変更）");
    }

    private void refreshPanel() {
        if (ClickerService.instance != null) ClickerService.instance.render();
    }

    // ---------- 登録画像の一覧 ----------

    private void renderList() {
        listBox.removeAllViews();
        tpls = TemplateStore.meta(this);
        if (tpls.isEmpty()) {
            listBox.addView(tv("まだありません。ゲーム画面でパネルの「📷 登録」を押してください。", 14));
            return;
        }
        for (int i = 0; i < tpls.size(); i++) listBox.addView(item(i));
    }

    private View item(final int i) {
        final TemplateStore.Tpl t = tpls.get(i);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        ImageView iv = new ImageView(this);
        Bitmap b = BitmapFactory.decodeFile(TemplateStore.file(this, t.id).getAbsolutePath());
        if (b != null) iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFFE6E6E6);
        row.addView(iv, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, 0, 0);

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextSize(15);
        name.setText(t.name);
        name.addTextChangedListener(watcher(s -> {
            String n = s.trim();
            t.name = n.isEmpty() ? "画像" : n;
            TemplateStore.save(this, tpls);
        }));
        col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox on = new CheckBox(this);
        on.setText("使う");
        on.setChecked(t.enabled);
        on.setOnCheckedChangeListener((cb, c) -> {
            t.enabled = c;
            TemplateStore.save(this, tpls);
        });
        btns.addView(on);
        btns.addView(small("▲", v -> move(i, -1)));
        btns.addView(small("▼", v -> move(i, 1)));
        btns.addView(small("削除", v -> new AlertDialog.Builder(this)
                .setMessage("「" + t.name + "」を削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    TemplateStore.delete(this, tpls, t);
                    renderList();
                })
                .setNegativeButton("やめる", null)
                .show()));
        col.addView(btns);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= tpls.size()) return;
        Collections.swap(tpls, i, j);
        TemplateStore.save(this, tpls);
        renderList();
    }
}
