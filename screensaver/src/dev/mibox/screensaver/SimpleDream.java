package dev.mibox.screensaver;

import android.service.dreams.DreamService;

/** The screensaver: fullscreen, non-interactive (any key wakes the TV). */
public class SimpleDream extends DreamService {
    private SaverView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(!Prefs.get(this).getBoolean(Prefs.DIM, false));
        view = new SaverView(this);
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
}
