package com.pratham.tvhome;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/** The TV's screensaver: tvOS-style Aerials. OK shows where it is, Left/Right skips, anything else wakes. */
public class AerialDream extends DreamService {
    private AerialView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        view = new AerialView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (view != null && view.handleKey(e)) return true;
        wakeUp();
        return true;
    }
}
