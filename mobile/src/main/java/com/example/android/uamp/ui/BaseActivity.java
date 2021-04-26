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
package com.example.android.uamp.ui;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.example.android.uamp.MusicService;
import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.NetworkHelper;
import com.example.android.uamp.utils.ResourceHelper;

/**
 * 当音乐播放时，下方需要展示播放控制条的Activity抽象基类
 * 同时封装了MediaBrowse的创建、连接、断连以及MediaController的创建等操作
 */
public abstract class BaseActivity extends ActionBarCastActivity implements MediaBrowserProvider {

    private static final String TAG = LogHelper.makeLogTag(BaseActivity.class);

    /** 播放控制条Fragment */
    private PlaybackControlsFragment mControlsFragment;
    /** MediaBrowse */
    private MediaBrowserCompat mMediaBrowser;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LogHelper.d(TAG, "Activity onCreate");

        if (Build.VERSION.SDK_INT >= 21) {
            // Since our app icon has the same color as colorPrimary, our entry in the Recent Apps
            // list gets weird. We need to change either the icon or the color of the TaskDescription.
            ActivityManager.TaskDescription taskDesc = new ActivityManager.TaskDescription(
                    getTitle().toString(),
                    BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher_white),
                    ResourceHelper.getThemeColor(this, R.attr.colorPrimary,
                            android.R.color.darker_gray));
            setTaskDescription(taskDesc);
        }

        // Connect a media browser just to get the media session token. There are other ways
        // this can be done, for example by sharing the session token directly.
        // MediaBrowserService作为参数创建MediaBrowser，这个Service会在MediaBrowser调用connect()时通过bindService()方式启动
        mMediaBrowser = new MediaBrowserCompat(this,
                new ComponentName(this, MusicService.class), mConnectionCallback, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LogHelper.d(TAG, "Activity onStart");

        mControlsFragment = (PlaybackControlsFragment) getFragmentManager().findFragmentById(R.id.fragment_playback_controls);
        if (mControlsFragment == null) {
            throw new IllegalStateException("Mising fragment with id 'controls'. Cannot continue.");
        }
        hidePlaybackControls();
        mMediaBrowser.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogHelper.d(TAG, "Activity onStop");
        MediaControllerCompat mediaController = MediaControllerCompat.getMediaController(this);
        if (mediaController != null) {
            mediaController.unregisterCallback(mControllerCallback);
        }
        mMediaBrowser.disconnect();
    }

    @Override
    public MediaBrowserCompat getMediaBrowser() {
        return mMediaBrowser;
    }

    /**
     * MediaBrowser连接状态回调
     */
    private final MediaBrowserCompat.ConnectionCallback mConnectionCallback =
            new MediaBrowserCompat.ConnectionCallback() {
                @Override
                public void onConnected() {
                    LogHelper.d(TAG, "onConnected");
                    try {
                        // 连接MediaBrowserService成功时，先创建MediaController，然后再分发事件
                        // 创建MediaController时需要通过sessionToken指定被控制的MediaSession
                        // MediaSession的创建发生在MediaBrowserService的onCreate()中
                        // 并通过MediaBrowserService.setSessionToken()将sessionToken存起来
                        // 因此当MediaBrowser与MediaBrowserService连接成功时可以通过MediaBrowser获取到sessionToken
                        MediaControllerCompat mediaController = new MediaControllerCompat(BaseActivity.this,
                                mMediaBrowser.getSessionToken());
                        MediaControllerCompat.setMediaController(BaseActivity.this, mediaController);
                        mediaController.registerCallback(mControllerCallback);

                        if (shouldShowControls()) {
                            showPlaybackControls();
                        } else {
                            LogHelper.d(TAG, "connectionCallback.onConnected: " +
                                    "hiding controls because metadata is null");
                            hidePlaybackControls();
                        }
                        // 分发到播放控制条Fragment
                        if (mControlsFragment != null) {
                            mControlsFragment.onConnected();
                        }
                        // 分发到子类
                        BaseActivity.this.onConnected();
                    } catch (RemoteException e) {
                        LogHelper.e(TAG, e, "could not connect media controller");
                        hidePlaybackControls();
                    }
                }
            };


    /**
     * MediaController回调，接收MediaSession的状态、数据变化
     */
    private final MediaControllerCompat.Callback mControllerCallback =
            new MediaControllerCompat.Callback() {
                @Override
                public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
                    if (shouldShowControls()) {
                        showPlaybackControls();
                    } else {
                        LogHelper.d(TAG, "mediaControllerCallback.onPlaybackStateChanged: " +
                                "hiding controls because state is ", state.getState());
                        hidePlaybackControls();
                    }
                }

                @Override
                public void onMetadataChanged(MediaMetadataCompat metadata) {
                    if (shouldShowControls()) {
                        showPlaybackControls();
                    } else {
                        LogHelper.d(TAG, "mediaControllerCallback.onMetadataChanged: " +
                                "hiding controls because metadata is null");
                        hidePlaybackControls();
                    }
                }
            };

    /**
     * Check if the MediaSession is active and in a "playback-able" state
     * (not NONE and not STOPPED).
     *
     * @return true if the MediaSession's state requires playback controls to be visible.
     */
    private boolean shouldShowControls() {
        MediaControllerCompat mediaController = MediaControllerCompat.getMediaController(this);
        if (mediaController == null ||
                mediaController.getMetadata() == null ||
                mediaController.getPlaybackState() == null) {
            return false;
        }
        switch (mediaController.getPlaybackState().getState()) {
            case PlaybackStateCompat.STATE_ERROR:
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
                return false;
            default:
                return true;
        }
    }

    /**
     * 展示播放控制条
     */
    private void showPlaybackControls() {
        LogHelper.d(TAG, "showPlaybackControls");
        if (NetworkHelper.isOnline(this)) {
            getFragmentManager().beginTransaction()
                    .setCustomAnimations(
                            R.animator.slide_in_from_bottom, R.animator.slide_out_to_bottom,
                            R.animator.slide_in_from_bottom, R.animator.slide_out_to_bottom)
                    .show(mControlsFragment)
                    .commit();
        }
    }

    /**
     * 隐藏播放控制条
     */
    private void hidePlaybackControls() {
        LogHelper.d(TAG, "hidePlaybackControls");
        getFragmentManager().beginTransaction().hide(mControlsFragment).commit();
    }


    /**
     * MediaBrowser成功连接到MediaBrowserService
     */
    protected void onConnected() {
        // empty implementation, can be overridden by clients.
    }

}
