package com.iven.musicplayer_pruszekszymon.playback;

import android.app.Activity;
import android.media.MediaPlayer;

import com.iven.musicplayer_pruszekszymon.models.Album;
import com.iven.musicplayer_pruszekszymon.models.Song;

import java.util.List;
public interface PlayerAdapter {

    void initMediaPlayer();

    void release();

    boolean isMediaPlayer();

    boolean isPlaying();

    void resumeOrPause();

    void reset();

    boolean isReset();

    void instantReset();

    void skip(boolean isNext);

    void openEqualizer(Activity activity);

    void seekTo(int position);

    void setPlaybackInfoListener(PlaybackInfoListener playbackInfoListener);

    Song getCurrentSong();

    @PlaybackInfoListener.State
    int getState();

    int getPlayerPosition();

    void registerNotificationActionsReceiver(boolean isRegister);

    Album getSelectedAlbum();

    void setSelectedAlbum(Album album);

    void setCurrentSong(Song song, List<Song> songs);

    MediaPlayer getMediaPlayer();

    void onPauseActivity();

    void onResumeActivity();
}
