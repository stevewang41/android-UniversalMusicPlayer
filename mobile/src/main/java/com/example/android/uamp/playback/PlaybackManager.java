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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.example.android.uamp.R;
import com.example.android.uamp.model.MusicProvider;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.example.android.uamp.utils.WearHelper;

/**
 * Manage the interactions among the container service, the queue manager and the actual playback.
 * <p>
 * 原本音频播放器是运行在 MediaBrowserService 中的，但是为了将服务层与播放控制层进行解耦，UAMP将播放器的控制逻辑放到了 {@link Playback} 实例中
 * 然后让该类作为中介通过接口回调的方式分别与 MediaBrowserService（通过 {@link PlaybackServiceCallback}）、
 * MediaSession（通过 {@link MediaSessionCallback}）以及 Playback（通过 {@link Playback.Callback}）关联，从而管理它们之间的交互。
 * <p>
 * 即UAMP的播放控制流程可以分为 指令下发 和 状态回传 两个过程：
 * 1）指令下发可以理解为从客户端UI层到Playback层每一层通过调用下一层的实例的方法将控制指令一直传达到播放器，从而达到UI组件控制播放器播放音乐的功能
 * 以点击播放按钮为例，播放指令下发过程中调用的方法顺序大致为：OnClickListener.onClick() →
 * MediaController.getTransportControls().play() → MediaSession.Callback.onPlay() → Playback.play()
 * <p>
 * 2）状态回传则是指下层通过上层实现的回调将播放状态一路回传到UI层中，用以更新UI组件的显示
 * Playback.Callback.onPlaybackStateUpdated() → PlaybackServiceCallback.onPlaybackStateUpdated() →
 * MediaSession.setPlaybackState(newState) → MediaController.Callback.onPlaybackStateChanged() → updateUI()
 */
public class PlaybackManager implements Playback.Callback {

    private static final String TAG = LogHelper.makeLogTag(PlaybackManager.class);
    /** 自定义点赞Action，即喜欢 */
    private static final String CUSTOM_ACTION_THUMBS_UP = "com.example.android.uamp.THUMBS_UP";
    /** 用来获取应用资源 */
    private final Resources mResources;
    /** 歌曲数据提供者 */
    private final MusicProvider mMusicProvider;
    /** 与 MusicService 交互的接口 */
    private final PlaybackServiceCallback mServiceCallback;
    /** 播放队列管理器 */
    private final QueueManager mQueueManager;
    /** Playback实例，默认为 {@link LocalPlayback} */
    private Playback mPlayback;
    /** 受控端回调 */
    private final MediaSessionCallback mSessionCallback;


    /**
     * 构造方法
     *
     * @param resources     应用资源
     * @param musicProvider 数据提供者
     * @param callback      回调接口
     * @param queueManager  队列管理器
     * @param playback      播放控制层接口，采用注入方式方便进行单元测试
     */
    public PlaybackManager(Resources resources,
                           MusicProvider musicProvider,
                           PlaybackServiceCallback callback,
                           QueueManager queueManager,
                           Playback playback) {
        mResources = resources;
        mMusicProvider = musicProvider;
        mServiceCallback = callback;
        mQueueManager = queueManager;
        mPlayback = playback;
        mPlayback.setCallback(this);    // 拿到Playback的实例后，将自身作为回调传入，完成与Playback双向关联
        mSessionCallback = new MediaSessionCallback();
    }

    /**
     * Handle a request to play music
     */
    public void handlePlayRequest() {
        LogHelper.d(TAG, "handlePlayRequest: mState=" + mPlayback.getState());
        MediaSessionCompat.QueueItem currentMusic = mQueueManager.getCurrentMusic();
        if (currentMusic != null) {
            mServiceCallback.onPlaybackStart();
            mPlayback.play(currentMusic);
        }
    }

    /**
     * Handle a request to pause music
     */
    public void handlePauseRequest() {
        LogHelper.d(TAG, "handlePauseRequest: mState=" + mPlayback.getState());
        if (mPlayback.isPlaying()) {
            mPlayback.pause();
            mServiceCallback.onPlaybackStop();
        }
    }

    /**
     * Handle a request to stop music
     *
     * @param withError Error message in case the stop has an unexpected cause. The error
     *                  message will be set in the PlaybackState and will be visible to
     *                  MediaController clients.
     */
    public void handleStopRequest(String withError) {
        LogHelper.d(TAG, "handleStopRequest: mState=" + mPlayback.getState() + " error=", withError);
        mPlayback.stop(true);
        mServiceCallback.onPlaybackStop();
        updatePlaybackState(withError);
    }


    /**
     * Update the current media player state, optionally showing an error message.
     *
     * @param error if not null, error message to present to the user.
     */
    public void updatePlaybackState(String error) {
        LogHelper.d(TAG, "updatePlaybackState, playback state=" + mPlayback.getState());
        long position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
        if (mPlayback != null && mPlayback.isConnected()) {
            position = mPlayback.getCurrentStreamPosition();
        }

        //noinspection ResourceType
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder().setActions(getAvailableActions());
        setCustomAction(stateBuilder);
        int state = mPlayback.getState();

        // If there is an error message, send it to the playback state:
        if (error != null) {
            // Error states are really only supposed to be used for errors that cause playback to
            // stop unexpectedly and persist until the user takes action to fix it.
            stateBuilder.setErrorMessage(error);
            state = PlaybackStateCompat.STATE_ERROR;
        }
        //noinspection ResourceType
        stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());

        // Set the activeQueueItemId if the current index is valid.
        MediaSessionCompat.QueueItem currentMusic = mQueueManager.getCurrentMusic();
        if (currentMusic != null) {
            stateBuilder.setActiveQueueItemId(currentMusic.getQueueId());
        }

        mServiceCallback.onPlaybackStateUpdated(stateBuilder.build());
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED) {
            mServiceCallback.onNotificationRequired();
        }
    }

    /**
     * 获取当前可用的Action
     *
     * @return
     */
    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (mPlayback.isPlaying()) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        } else {
            actions |= PlaybackStateCompat.ACTION_PLAY;
        }
        return actions;
    }

    /**
     * 设置自定义Action，这里只定义了一个 {@link #CUSTOM_ACTION_THUMBS_UP}
     *
     * @param stateBuilder
     */
    private void setCustomAction(PlaybackStateCompat.Builder stateBuilder) {
        MediaSessionCompat.QueueItem currentMusic = mQueueManager.getCurrentMusic();
        if (currentMusic == null) {
            return;
        }
        // Set appropriate "Favorite" icon on Custom action:
        String mediaId = currentMusic.getDescription().getMediaId();
        if (mediaId == null) {
            return;
        }
        // 从mediaId中提取出唯一的musicId
        String musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId);
        int favoriteIcon = mMusicProvider.isFavorite(musicId) ? R.drawable.ic_star_on : R.drawable.ic_star_off;
        LogHelper.d(TAG, "updatePlaybackState, setting Favorite custom action of music ",
                musicId, " current favorite=", mMusicProvider.isFavorite(musicId));
        Bundle customActionExtras = new Bundle();
        WearHelper.setShowCustomActionOnWear(customActionExtras, true);
        stateBuilder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_THUMBS_UP,
                mResources.getString(R.string.favorite), favoriteIcon)
                .setExtras(customActionExtras)
                .build());
    }

    /**
     * Implementation of the Playback.Callback interface
     */
    @Override
    public void setCurrentMediaId(String mediaId) {
        LogHelper.d(TAG, "setCurrentMediaId", mediaId);
        mQueueManager.setQueueFromMusic(mediaId);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        updatePlaybackState(null);
    }

    @Override
    public void onCompletion() {
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        if (mQueueManager.skipQueuePosition(1)) {
            handlePlayRequest();
            mQueueManager.updateMetadata();
        } else {
            // If skipping was not possible, we stop and release the resources:
            handleStopRequest(null);
        }
    }

    @Override
    public void onError(String error) {
        updatePlaybackState(error);
    }


    /**
     * Switch to a different Playback instance, maintaining all playback state, if possible.
     *
     * @param playback switch to this playback
     */
    public void switchToPlayback(Playback playback, boolean resumePlaying) {
        if (playback == null) {
            throw new IllegalArgumentException("Playback cannot be null");
        }
        // Suspends current state.
        int oldState = mPlayback.getState();
        long pos = mPlayback.getCurrentStreamPosition();
        String currentMediaId = mPlayback.getCurrentMediaId();
        mPlayback.stop(false);
        playback.setCallback(this);
        playback.setCurrentMediaId(currentMediaId);
        playback.seekTo(pos < 0 ? 0 : pos);
        playback.start();
        // Swaps instance.
        mPlayback = playback;
        switch (oldState) {
            case PlaybackStateCompat.STATE_BUFFERING:
            case PlaybackStateCompat.STATE_CONNECTING:
            case PlaybackStateCompat.STATE_PAUSED:
                mPlayback.pause();
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                MediaSessionCompat.QueueItem currentMusic = mQueueManager.getCurrentMusic();
                if (resumePlaying && currentMusic != null) {
                    mPlayback.play(currentMusic);
                } else if (!resumePlaying) {
                    mPlayback.pause();
                } else {
                    mPlayback.stop(true);
                }
                break;
            case PlaybackStateCompat.STATE_NONE:
                break;
            default:
                LogHelper.d(TAG, "Default called. Old state is ", oldState);
        }
    }

    /**
     * 获取 Playback 实例
     *
     * @return
     */
    public Playback getPlayback() {
        return mPlayback;
    }

    /**
     * 获取受控端回调
     *
     * @return 受控端回调
     */
    public MediaSessionCallback getMediaSessionCallback() {
        return mSessionCallback;
    }

    /**
     * 受控端回调实现类
     */
    private class MediaSessionCallback extends MediaSessionCompat.Callback {

        @Override
        public void onPlay() {
            LogHelper.d(TAG, "play");
            if (mQueueManager.getCurrentMusic() == null) {
                mQueueManager.setRandomQueue();
            }
            handlePlayRequest();
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            LogHelper.d(TAG, "playFromMediaId mediaId:", mediaId, "  extras=", extras);
            mQueueManager.setQueueFromMusic(mediaId);
            handlePlayRequest();
        }

        /**
         * Handle free and contextual searches.
         * <p/>
         * All voice searches on Android Auto are sent to this method through a connected
         * {@link android.support.v4.media.session.MediaControllerCompat}.
         * <p/>
         * Threads and async handling:
         * Search, as a potentially slow operation, should run in another thread.
         * <p/>
         * Since this method runs on the main thread, most apps with non-trivial metadata
         * should defer the actual search to another thread (for example, by using
         * an {@link AsyncTask} as we do here).
         **/
        @Override
        public void onPlayFromSearch(final String query, final Bundle extras) {
            LogHelper.d(TAG, "playFromSearch  query=", query, " extras=", extras);
            mPlayback.setState(PlaybackStateCompat.STATE_CONNECTING);
            mMusicProvider.retrieveMediaAsync(new MusicProvider.Callback() {
                @Override
                public void onMusicCatalogReady(boolean success) {
                    if (!success) {
                        updatePlaybackState("Could not load catalog");
                    }
                    boolean successSearch = mQueueManager.setQueueFromSearch(query, extras);
                    if (successSearch) {
                        handlePlayRequest();
                        mQueueManager.updateMetadata();
                    } else {
                        updatePlaybackState("Could not find music");
                    }
                }
            });
        }

        @Override
        public void onSeekTo(long position) {
            LogHelper.d(TAG, "onSeekTo:", position);
            mPlayback.seekTo((int) position);
        }


        @Override
        public void onPause() {
            LogHelper.d(TAG, "pause. current state=" + mPlayback.getState());
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            LogHelper.d(TAG, "stop. current state=" + mPlayback.getState());
            handleStopRequest(null);
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            LogHelper.d(TAG, "OnSkipToQueueItem:" + queueId);
            mQueueManager.setCurrentQueueItem(queueId);
            mQueueManager.updateMetadata();
        }

        @Override
        public void onSkipToNext() {
            LogHelper.d(TAG, "skipToNext");
            if (mQueueManager.skipQueuePosition(1)) {
                handlePlayRequest();
            } else {
                handleStopRequest("Cannot skip");
            }
            mQueueManager.updateMetadata();
        }

        @Override
        public void onSkipToPrevious() {
            if (mQueueManager.skipQueuePosition(-1)) {
                handlePlayRequest();
            } else {
                handleStopRequest("Cannot skip");
            }
            mQueueManager.updateMetadata();
        }

        @Override
        public void onCustomAction(@NonNull String action, Bundle extras) {
            if (CUSTOM_ACTION_THUMBS_UP.equals(action)) {
                LogHelper.i(TAG, "onCustomAction: favorite for current track");
                MediaSessionCompat.QueueItem currentMusic = mQueueManager.getCurrentMusic();
                if (currentMusic != null) {
                    String mediaId = currentMusic.getDescription().getMediaId();
                    if (mediaId != null) {
                        String musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId);
                        mMusicProvider.setFavorite(musicId, !mMusicProvider.isFavorite(musicId));
                    }
                }
                // playback state needs to be updated because the "Favorite" icon on the
                // custom action will change to reflect the new favorite state.
                updatePlaybackState(null);
            } else {
                LogHelper.e(TAG, "Unsupported action: ", action);
            }
        }
    }
}
