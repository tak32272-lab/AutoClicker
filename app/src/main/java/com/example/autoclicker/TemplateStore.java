package com.example.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 登録した画像の保存・読み込み（画像は PNG、一覧は設定に JSON で保存） */
final class TemplateStore {

    private TemplateStore() { }

    static final class Tpl {
        String id, name;
        boolean enabled = true;
        int w, h;
        int[] argb;

        private int cacheF = -1;
        private ImageMatcher.Template cache;

        /** 画面の縮小倍率 f に合わせた照合用データ（作ったものは覚えておく） */
        ImageMatcher.Template template(int f) {
            if (f != cacheF) {
                cacheF = f;
                cache = null;
                if (argb != null && w / f >= 3 && h / f >= 3) {
                    ImageMatcher.Template t = ImageMatcher.Template.create(argb, w, h, f, true);
                    if (t.usable()) cache = t;
                }
            }
            return cache;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ac", Context.MODE_PRIVATE);
    }

    static File file(Context c, String id) {
        File d = new File(c.getFilesDir(), "templates");
        if (!d.exists()) d.mkdirs();
        return new File(d, id + ".png");
    }

    /** 一覧（画像の中身は読まない） */
    static List<Tpl> meta(Context c) {
        List<Tpl> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("tpls", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tpl t = new Tpl();
                t.id = o.getString("id");
                t.name = o.optString("name", "画像");
                t.enabled = o.optBoolean("on", true);
                if (file(c, t.id).exists()) out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 一覧＋画像の中身 */
    static List<Tpl> load(Context c) {
        List<Tpl> out = new ArrayList<>();
        for (Tpl t : meta(c)) {
            Bitmap b = BitmapFactory.decodeFile(file(c, t.id).getAbsolutePath());
            if (b == null) continue;
            t.w = b.getWidth();
            t.h = b.getHeight();
            t.argb = new int[t.w * t.h];
            b.getPixels(t.argb, 0, t.w, 0, 0, t.w, t.h);
            b.recycle();
            out.add(t);
        }
        return out;
    }

    static void save(Context c, List<Tpl> list) {
        try {
            JSONArray a = new JSONArray();
            for (Tpl t : list) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("name", t.name);
                o.put("on", t.enabled);
                a.put(o);
            }
            prefs(c).edit().putString("tpls", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 新しい画像を一番下（優先度いちばん低い）に追加して、名前を返す。失敗なら null */
    static String add(Context c, Bitmap crop) {
        List<Tpl> list = meta(c);
        Tpl t = new Tpl();
        t.id = "t" + System.currentTimeMillis();
        int n = list.size() + 1;
        String name;
        do { name = "画像" + n++; } while (hasName(list, name));
        t.name = name;
        try (FileOutputStream os = new FileOutputStream(file(c, t.id))) {
            if (!crop.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
        } catch (Exception e) {
            return null;
        }
        list.add(t);
        save(c, list);
        return t.name;
    }

    static void delete(Context c, List<Tpl> list, Tpl t) {
        list.remove(t);
        file(c, t.id).delete();
        save(c, list);
    }

    private static boolean hasName(List<Tpl> list, String name) {
        for (Tpl t : list) if (name.equals(t.name)) return true;
        return false;
    }
}
