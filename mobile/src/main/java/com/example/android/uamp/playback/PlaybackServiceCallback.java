package com.example.android.uamp.playback;

import android.support.v4.media.session.PlaybackStateCompat;

/**
 * {@link PlaybackManager } 与 {@link com.example.android.uamp.MusicService } 交互接口
 */
public interface PlaybackServiceCallback {

    /**
     * 播放流程开始
     */
    void onPlaybackStart();

    /**
     * 需要启动通知栏
     */
    void onNotificationRequired();

    /**
     * 播放状态更新
     *
     * @param newState 当前播放状态
     */
    void onPlaybackStateUpdated(PlaybackStateCompat newState);

    /**
     * 播放流程停止，包括暂停和停止
     */
    void onPlaybackStop();
}
