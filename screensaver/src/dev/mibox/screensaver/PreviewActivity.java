package dev.mibox.screensaver;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Shows the screensaver as it will look; any key closes it. */
public class PreviewActivity extends Activity {
    private SaverView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new SaverView(this);
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
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
