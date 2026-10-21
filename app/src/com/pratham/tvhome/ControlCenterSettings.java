package com.pratham.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Customise Control Center: switch each control on or off. Sleep is always shown. */
public class ControlCenterSettings extends Activity {
    private final List<TextView> values = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setColorFilter(0xFF8A8A8A, android.graphics.PorterDuff.Mode.MULTIPLY);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            try {
                android.content.SharedPreferences p = Profiles.prefs(this);
                int i = Math.floorMod(p.getInt(Profiles.key(p, "wall"), Calendar.getInstance().get(Calendar.DAY_OF_YEAR)),
                        Backdrop.WALLPAPERS.length);
                Bitmap[] b = Backdrop.load(this, i, 1920, 1080);
                b[0].recycle();
                runOnUiThread(() -> bg.setImageBitmap(b[1]));
            } catch (Exception ignored) {
            }
        }).start();

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(0, Ui.dp(48), 0, Ui.dp(48));
        TextView title = Ui.text(this, 40, Ui.bold, Color.WHITE);
        title.setText("Control Center");
        col.addView(title);
        TextView sub = Ui.text(this, 16, Ui.medium, 0xB3FFFFFF);
        sub.setText("Choose which controls appear. Sleep is always there.");
        sub.setPadding(0, Ui.dp(10), 0, Ui.dp(22));
        col.addView(sub);

        View first = null;
        for (String[] it : ControlCenter.ITEMS) {
            final String id = it[0];
            String kind = "pill".equals(it[2]) ? "Tile" : "circle".equals(it[2]) ? "Button" : "Card";
            View row = row(col, it[1], kind, v -> {
                List<String> on = ControlCenter.enabled(this);
                if (on.contains(id)) on.remove(id);
                else on.add(id);
                ControlCenter.setEnabled(this, on);
                refresh();
            });
            if (first == null) first = row;
        }
        refresh();

        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(560), -1, Gravity.CENTER_HORIZONTAL);
        root.addView(scroll, lp);
        setContentView(root);
        if (first != null) first.requestFocus();
    }

    private void refresh() {
        List<String> on = ControlCenter.enabled(this);
        for (int i = 0; i < values.size(); i++) {
            values.get(i).setText(on.contains(ControlCenter.ITEMS[i][0]) ? "On" : "Off");
        }
    }

    private View row(LinearLayout col, String label, String kind, View.OnClickListener click) {
        GlassView item = new GlassView(this, 22).plain();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        TextView t = Ui.text(this, 19, Ui.semibold, Color.WHITE);
        t.setText(label);
        r.addView(t);
        TextView k = Ui.text(this, 14, Ui.medium, 0x80FFFFFF);
        k.setTag(0x80FFFFFF);
        k.setText("  ·  " + kind);
        r.addView(k, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, 17, Ui.medium, 0xB3FFFFFF);
        v.setTag(0xB3FFFFFF);
        r.addView(v);
        values.add(v);
        item.addView(r, new FrameLayout.LayoutParams(-1, -1));
        item.button(click, new TextView[] {t, k, v}, new ImageView[0]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(52));
        lp.topMargin = Ui.dp(4);
        col.addView(item, lp);
        return item;
    }
}
