package com.example.android.uamp.playback;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import java.util.List;

/**
 * {@link QueueManager } 与 {@link com.example.android.uamp.MusicService } 交互接口
 */
public interface QueueServiceCallback {

    /**
     * 当前队列数据变更
     *
     * @param title    队列标题
     * @param newQueue 队列数据
     */
    void onQueueUpdated(String title, List<MediaSessionCompat.QueueItem> newQueue);

    /**
     * 当前歌曲数据变更
     *
     * @param metadata 歌曲数据
     */
    void onMetadataChanged(MediaMetadataCompat metadata);

    /**
     * 当前队列下索引发生变更
     *
     * @param queueIndex
     */
    void onCurrentQueueIndexUpdated(int queueIndex);

    /**
     * 歌曲数据检索失败
     */
    void onMetadataRetrieveError();
}
