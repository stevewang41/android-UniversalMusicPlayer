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

import com.example.android.uamp.MusicService;

import static android.support.v4.media.session.MediaSessionCompat.QueueItem;

/**
 * Interface representing either Local or Remote Playback. The {@link MusicService} works
 * directly with an instance of the Playback object to make the various calls such as
 * play, pause etc.
 * <p>
 * 播放控制层接口
 */
public interface Playback {

    /**
     * 设置回调
     *
     * @param callback
     */
    void setCallback(Callback callback);

    /**
     * Start/setup the playback.
     * Resources/listeners would be allocated by implementations.
     */
    void start();

    void play(QueueItem item);

    void seekTo(long position);

    void pause();

    /**
     * Stop the playback. All resources can be de-allocated by implementations here.
     *
     * @param notifyListeners if true and a callback has been set by setCallback,
     *                        callback.onPlaybackStatusChanged will be called after changing
     *                        the state.
     */
    void stop(boolean notifyListeners);

    /**
     * Set the latest playback state as determined by the caller.
     */
    void setState(int state);

    /**
     * Get the current {@link android.media.session.PlaybackState#getState()}
     */
    int getState();

    /**
     * @return boolean that indicates that this is ready to be used.
     */
    boolean isConnected();

    /**
     * @return boolean indicating whether the player is playing or is supposed to be
     * playing when we gain audio focus.
     */
    boolean isPlaying();

    /**
     * Returns the playback position in the current window, in milliseconds.
     *
     * @return pos if currently playing an item
     */
    long getCurrentStreamPosition();

    /**
     * Queries the underlying stream and update the internal last known stream position.
     */
    void updateLastKnownStreamPosition();

    /**
     * 设置当前播放的媒体数据id
     *
     * @param mediaId 媒体数据id
     */
    void setCurrentMediaId(String mediaId);

    String getCurrentMediaId();

    /**
     * 与 {@link PlaybackManager } 交互接口
     */
    interface Callback {

        /**
         * @param mediaId being currently played
         */
        void setCurrentMediaId(String mediaId);

        /**
         * on Playback status changed
         * Implementations can use this callback to update
         * playback state on the media sessions.
         */
        void onPlaybackStateChanged(int state);

        /**
         * On current music completed.
         */
        void onCompletion();

        /**
         * @param error to be added to the PlaybackState
         */
        void onError(String error);
    }
}
