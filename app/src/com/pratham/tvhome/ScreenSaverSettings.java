package com.pratham.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/** tvOS-style Screen Saver settings: Preview, and Choose Aerials by category. */
public class ScreenSaverSettings extends Activity {
    private final TextView[] values = new TextView[AerialView.CATEGORIES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false); // focused rows grow past the column; don't square their corners

        // Blurred wallpaper backdrop, like every tvOS settings screen.
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

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        TextView title = Ui.text(this, 40, Ui.bold, Color.WHITE);
        title.setText("Screen Saver");
        col.addView(title);
        TextView sub = Ui.text(this, 16, Ui.medium, 0xB3FFFFFF);
        sub.setText("Aerials — choose what plays when the TV is idle");
        sub.setPadding(0, Ui.dp(10), 0, Ui.dp(28));
        col.addView(sub);

        View first = row(col, "Preview", null, v -> startActivity(new Intent(this, AerialActivity.class)));
        TextView head = Ui.text(this, 14, Ui.semibold, 0x99FFFFFF);
        head.setText("CHOOSE AERIALS");
        head.setPadding(Ui.dp(18), Ui.dp(26), 0, Ui.dp(8));
        col.addView(head);
        for (int i = 0; i < AerialView.CATEGORIES.length; i++) {
            final int bit = 1 << i;
            final int idx = i;
            row(col, AerialView.CATEGORIES[i] + "  ·  " + count(i) + " clips", values, v -> {
                int mask = AerialView.offMask(this) ^ bit;
                if (mask == (1 << AerialView.CATEGORIES.length) - 1) return; // keep at least one on
                AerialView.setOffMask(this, mask);
                refresh();
            }, idx);
        }
        refresh();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(520), -2, Gravity.CENTER);
        root.addView(col, lp);
        setContentView(root);
        first.requestFocus();
    }

    private static int count(int category) {
        int n = 0;
        for (String[] c : AerialView.CLIPS) if (Integer.parseInt(c[4]) == category) n++;
        return n;
    }

    private void refresh() {
        int off = AerialView.offMask(this);
        for (int i = 0; i < values.length; i++) {
            values[i].setText((off & (1 << i)) == 0 ? "On" : "Off");
        }
    }

    private View row(LinearLayout col, String label, TextView[] valueOut, View.OnClickListener click) {
        return row(col, label, valueOut, click, -1);
    }

    private View row(LinearLayout col, String label, TextView[] valueOut, View.OnClickListener click, int idx) {
        GlassView item = new GlassView(this, 22).plain();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        TextView t = Ui.text(this, 19, Ui.semibold, Color.WHITE);
        t.setText(label);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, 17, Ui.medium, 0x0); // colour set by GlassView when lit
        v.setTag(0xB3FFFFFF);
        v.setTextColor(0xB3FFFFFF);
        r.addView(v);
        if (valueOut != null && idx >= 0) valueOut[idx] = v;
        item.addView(r, new FrameLayout.LayoutParams(-1, -1));
        item.button(click, new TextView[] {t, v}, new ImageView[0]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(54));
        lp.topMargin = Ui.dp(4);
        col.addView(item, lp);
        return item;
    }
}
