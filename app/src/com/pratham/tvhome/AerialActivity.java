package com.pratham.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** Starts the Aerials screensaver right away (Control Center → Screen Saver). */
public class AerialActivity extends Activity {
    private AerialView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        view = new AerialView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (view.handleKey(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
