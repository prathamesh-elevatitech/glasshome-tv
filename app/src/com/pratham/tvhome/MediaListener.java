package com.pratham.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Does nothing with notifications; being an enabled listener is what lets Control Center see
 * the active media session for its Now Playing card
 * (adb shell cmd notification allow_listener com.pratham.tvhome/com.pratham.tvhome.MediaListener).
 */
public class MediaListener extends NotificationListenerService {

    /** The session that is playing (or else paused), or null if none / not permitted. */
    static MediaController nowPlaying(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, MediaListener.class));
            MediaController paused = null;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (paused == null && (st.getState() == PlaybackState.STATE_PAUSED
                        || st.getState() == PlaybackState.STATE_BUFFERING)) {
                    paused = mc;
                }
            }
            return paused;
        } catch (SecurityException e) {
            return null;
        }
    }
}
