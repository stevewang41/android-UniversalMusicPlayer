/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.playback;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import com.example.android.uamp.AlbumArtCache;
import com.example.android.uamp.R;
import com.example.android.uamp.model.MusicProvider;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.example.android.uamp.utils.QueueHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Simple data provider for queues. Keeps track of a current queue and a current index in the
 * queue. Also provides methods to set the current queue based on common queries, relying on a
 * given MusicProvider to provide the actual media metadata.
 * <p>
 * UAMP中数据层和播放控制层是分离开来的，正如使用{@link PlaybackManager} 作为中介管理播放器
 * 使用该类作为中介连通数据层、播放控制层和服务层，并提供了队列形式的存储容器（这个队列是线程安全的）以及可以管理音乐层级关系的方法
 */
public class QueueManager {
    private static final String TAG = LogHelper.makeLogTag(QueueManager.class);

    /** 用来获取应用资源 */
    private final Resources mResources;
    /** 歌曲数据提供者 */
    private final MusicProvider mMusicProvider;
    /** 与 MusicService 交互的接口 */
    private final QueueServiceCallback mServiceCallback;
    /** 当前播放队列 */
    private List<MediaSessionCompat.QueueItem> mPlayingQueue;
    /** 当前索引 */
    private int mCurrentIndex;

    /**
     * 构造方法
     *
     * @param resources     应用资源
     * @param musicProvider 数据提供者
     * @param callback      回调接口
     */
    public QueueManager(@NonNull Resources resources,
                        @NonNull MusicProvider musicProvider,
                        @NonNull QueueServiceCallback callback) {
        mResources = resources;
        mMusicProvider = musicProvider;
        mServiceCallback = callback;
        mPlayingQueue = Collections.synchronizedList(new ArrayList<MediaSessionCompat.QueueItem>());
        mCurrentIndex = 0;
    }

    /**
     * 设置随机播放队列
     */
    public void setRandomQueue() {
        setCurrentQueue(mResources.getString(R.string.random_queue_title), QueueHelper.getRandomQueue(mMusicProvider));
        updateMetadata();
    }

    /**
     * 根据指定曲目设置播放队列
     *
     * @param mediaId 媒体数据id
     */
    public void setQueueFromMusic(String mediaId) {
        LogHelper.d(TAG, "setQueueFromMusic", mediaId);

        // The mediaId used here is not the unique musicId. This one comes from the
        // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
        // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
        // so we can build the correct playing queue, based on where the track was selected from.
        boolean canReuseQueue = false;
        if (isSameBrowsingCategory(mediaId)) {
            canReuseQueue = setCurrentQueueItem(mediaId);
        }
        if (!canReuseQueue) {
            String queueTitle = mResources.getString(R.string.browse_musics_by_genre_subtitle,
                    MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            setCurrentQueue(queueTitle, QueueHelper.getPlayingQueue(mediaId, mMusicProvider), mediaId);
        }
        updateMetadata();
    }

    /**
     * 根据搜索内容设置播放队列
     *
     * @param query
     * @param extras
     * @return
     */
    public boolean setQueueFromSearch(String query, Bundle extras) {
        List<MediaSessionCompat.QueueItem> queue = QueueHelper.getPlayingQueueFromSearch(query, extras, mMusicProvider);
        setCurrentQueue(mResources.getString(R.string.search_queue_title), queue);
        updateMetadata();
        return queue != null && !queue.isEmpty();
    }

    /**
     * 设置当前播放队列
     *
     * @param title    队列标题
     * @param newQueue 队列数据
     */
    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue) {
        setCurrentQueue(title, newQueue, null);
    }

    /**
     * 设置当前播放队列
     *
     * @param title          队列标题
     * @param newQueue       队列数据
     * @param initialMediaId 初始音频数据id
     */
    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue, String initialMediaId) {
        mPlayingQueue = newQueue;
        int index = 0;
        if (initialMediaId != null) {
            index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, initialMediaId);
        }
        mCurrentIndex = Math.max(index, 0);
        mServiceCallback.onQueueUpdated(title, newQueue);
    }

    /**
     * 更新当前曲目数据
     */
    public void updateMetadata() {
        MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
        if (currentMusic == null) {
            mServiceCallback.onMetadataRetrieveError();
            return;
        }
        final String musicId = MediaIDHelper.extractMusicIDFromMediaID(currentMusic.getDescription().getMediaId());
        MediaMetadataCompat metadata = mMusicProvider.getMusic(musicId);
        if (metadata == null) {
            throw new IllegalArgumentException("Invalid musicId " + musicId);
        }
        mServiceCallback.onMetadataChanged(metadata);
        // Set the proper album artwork on the media session, so it can be shown in the locked screen and in other places.
        if (metadata.getDescription().getIconBitmap() == null && metadata.getDescription().getIconUri() != null) {
            String albumUri = metadata.getDescription().getIconUri().toString();
            AlbumArtCache.getInstance().fetch(albumUri, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    mMusicProvider.updateMusicArt(musicId, bitmap, icon);
                    // If we are still playing the same music, notify the listeners:
                    MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
                    if (currentMusic == null) {
                        return;
                    }
                    String currentMusicId = MediaIDHelper.extractMusicIDFromMediaID(currentMusic.getDescription().getMediaId());
                    if (musicId.equals(currentMusicId)) {
                        mServiceCallback.onMetadataChanged(mMusicProvider.getMusic(currentMusicId));
                    }
                }
            });
        }
    }

    /**
     * 判断指定媒体数据与当前曲目是否位于同一浏览目录下
     *
     * @param mediaId 媒体数据id
     * @return
     */
    public boolean isSameBrowsingCategory(@NonNull String mediaId) {
        String[] newBrowseHierarchy = MediaIDHelper.getHierarchy(mediaId);
        MediaSessionCompat.QueueItem current = getCurrentMusic();
        if (current == null) {
            return false;
        }
        String[] currentBrowseHierarchy = MediaIDHelper.getHierarchy(current.getDescription().getMediaId());
        return Arrays.equals(newBrowseHierarchy, currentBrowseHierarchy);
    }


    /**
     * 跳转到当前队列的指定目录下
     *
     * @param queueId 目录id
     * @return
     */
    public boolean setCurrentQueueItem(long queueId) {
        // set the current index on queue from the queue Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    /**
     * 跳转到当前队列的指定曲目
     *
     * @param mediaId 媒体数据id
     * @return
     */
    public boolean setCurrentQueueItem(String mediaId) {
        // set the current index on queue from the music Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, mediaId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    /**
     * 跳转到当前队列的指定索引项
     *
     * @param index 索引
     */
    private void setCurrentQueueIndex(int index) {
        if (index >= 0 && index < mPlayingQueue.size()) {
            mCurrentIndex = index;
            mServiceCallback.onCurrentQueueIndexUpdated(mCurrentIndex);
        }
    }

    /**
     * 移动索引位置
     *
     * @param distance 移动距离
     * @return 是否移动成功
     */
    public boolean skipQueuePosition(int distance) {
        int index = mCurrentIndex + distance;
        if (index < 0) {
            // skip backwards before the first song will keep you on the first song
            index = 0;
        } else {
            // skip forwards when in last song will cycle back to start of the queue
            index %= mPlayingQueue.size();
        }
        if (!QueueHelper.isIndexPlayable(mPlayingQueue, index)) {
            LogHelper.e(TAG, "Cannot increment queue index by ", distance,
                    ". Current=", mCurrentIndex, " queue length=", mPlayingQueue.size());
            return false;
        }
        mCurrentIndex = index;
        return true;
    }

    /**
     * 获取当前曲目
     *
     * @return 当前曲目
     */
    public MediaSessionCompat.QueueItem getCurrentMusic() {
        if (!QueueHelper.isIndexPlayable(mPlayingQueue, mCurrentIndex)) {
            return null;
        }
        return mPlayingQueue.get(mCurrentIndex);
    }


    /**
     * 获取当前播放队列长度
     *
     * @return 队列长度
     */
    public int getCurrentQueueSize() {
        if (mPlayingQueue == null) {
            return 0;
        }
        return mPlayingQueue.size();
    }
}
