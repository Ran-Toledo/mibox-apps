package dev.mibox.screensaver;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Now Playing may read media sessions: MediaSessionManager.getActiveSessions requires
 * the caller to have an approved notification listener. Approve it once with
 *   adb shell cmd notification allow_listener dev.mibox.screensaver/dev.mibox.screensaver.MediaListener
 * The approval is what counts, not the binding, so the listener unbinds itself as soon as the system
 * connects it: no notifications are read and the app's process is not kept running.
 */
public class MediaListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        requestUnbind();
    }
}
