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

import android.app.FragmentTransaction;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.text.TextUtils;

import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;

/**
 * 应用的MainActivity
 * <p>
 * 持有 MediaBrowser 和 MediaController 实例
 * 在 {@link #onCreate(Bundle)} } 时创建 MediaBrowser
 * 在 {@link #onStart()} 时 MediaBrowser 连接到 MediaBrowserService
 * 在 {@link #onStop()}  时 MediaBrowser 与 MediaBrowserService 断开连接
 * 因此，当音乐播放器在前台运行时，MediaBrowser 与 MediaBrowserService 始终处于连接状态
 */
public class MusicPlayerActivity extends BaseActivity implements MediaFragmentListener {

    private static final String TAG = LogHelper.makeLogTag(MusicPlayerActivity.class);
    private static final String SAVED_MEDIA_ID = "com.example.android.uamp.MEDIA_ID";
    /** Fragment Tag */
    private static final String FRAGMENT_TAG = "uamp_list_container";
    /** Intent Extra Key：描述启动此Activity时是否顺带启动全屏播放器 */
    public static final String EXTRA_START_FULLSCREEN = "com.example.android.uamp.EXTRA_START_FULLSCREEN";

    /**
     * Intent Extra Key：携带当前媒体数据的描述 MediaDescription
     * <p>
     * Optionally used with {@link #EXTRA_START_FULLSCREEN} to carry a MediaDescription to
     * the {@link FullScreenPlayerActivity}, speeding up the screen rendering
     * while the {@link android.support.v4.media.session.MediaControllerCompat} is connecting.
     */
    public static final String EXTRA_CURRENT_MEDIA_DESCRIPTION = "com.example.android.uamp.CURRENT_MEDIA_DESCRIPTION";
    /** 语音搜索参数 */
    private Bundle mVoiceSearchParams;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelper.d(TAG, "Activity onCreate");

        setContentView(R.layout.activity_player);
        initializeToolbar();

        initializeFromParams(savedInstanceState, getIntent());
        if (savedInstanceState == null) {
            // 如果此Activity不在前台，点击通知栏会走到这
            startFullScreenActivityIfNeeded(getIntent());
        }
    }

    /**
     * 点击通知栏会以singleTop模式启动此Activity
     * 详见{@link com.example.android.uamp.MediaNotificationManager#createContentIntent(MediaDescriptionCompat)}
     * 如果此时Activity正在前台，则会回调该方法
     */
    @Override
    protected void onNewIntent(Intent intent) {
        LogHelper.d(TAG, "onNewIntent, intent=" + intent);
        initializeFromParams(null, intent);
        startFullScreenActivityIfNeeded(intent);
    }

    /**
     * 根据参数初始化
     *
     * @param savedInstanceState
     * @param intent
     */
    private void initializeFromParams(Bundle savedInstanceState, Intent intent) {
        String mediaId = null;
        // check if we were started from a "Play XYZ" voice search. If so, we save the extras
        // (which contain the query details) in a parameter, so we can reuse it later, when the
        // MediaSession is connected.
        if (intent.getAction() != null && intent.getAction().equals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)) {
            mVoiceSearchParams = intent.getExtras();
            LogHelper.d(TAG, "Starting from voice search query=",
                    mVoiceSearchParams.getString(SearchManager.QUERY));
        } else {
            if (savedInstanceState != null) {
                // If there is a saved media ID, use it
                mediaId = savedInstanceState.getString(SAVED_MEDIA_ID);
            }
        }
        navigateToBrowserFragment(mediaId);
    }

    /**
     * 导航到媒体浏览Fragment
     *
     * @param mediaId 媒体数据id
     */
    private void navigateToBrowserFragment(String mediaId) {
        LogHelper.d(TAG, "navigateToBrowser, mediaId=" + mediaId);
        MediaBrowserFragment fragment = getBrowseFragment();
        if (fragment == null || !TextUtils.equals(fragment.getMediaId(), mediaId)) {
            fragment = new MediaBrowserFragment();
            fragment.setMediaId(mediaId);
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.setCustomAnimations(
                    R.animator.slide_in_from_right, R.animator.slide_out_to_left,
                    R.animator.slide_in_from_left, R.animator.slide_out_to_right);
            transaction.replace(R.id.container, fragment, FRAGMENT_TAG);
            // If this is not the top level media (root), we add it to the fragment back stack,
            // so that actionbar toggle and Back will work appropriately:
            if (mediaId != null) {
                transaction.addToBackStack(null);
            }
            transaction.commit();
        }
    }

    /**
     * 尝试启动全屏播放器
     *
     * @param intent The intent to start.
     */
    private void startFullScreenActivityIfNeeded(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START_FULLSCREEN, false)) {
            Intent fullScreenIntent = new Intent(this, FullScreenPlayerActivity.class);
            fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            MediaDescriptionCompat description = intent.getParcelableExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION);
            fullScreenIntent.putExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION, description);
            startActivity(fullScreenIntent);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        String mediaId = getMediaId();
        if (mediaId != null) {
            outState.putString(SAVED_MEDIA_ID, mediaId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onMediaItemSelected(MediaBrowserCompat.MediaItem item) {
        LogHelper.d(TAG, "onMediaItemSelected, mediaId=" + item.getMediaId());
        if (item.isPlayable()) {
            MediaControllerCompat.getMediaController(MusicPlayerActivity.this).getTransportControls()
                    .playFromMediaId(item.getMediaId(), null);
        } else if (item.isBrowsable()) {
            navigateToBrowserFragment(item.getMediaId());
        } else {
            LogHelper.w(TAG, "Ignoring MediaItem that is neither browsable nor playable: ",
                    "mediaId=", item.getMediaId());
        }
    }

    @Override
    public void setToolbarTitle(CharSequence title) {
        LogHelper.d(TAG, "Setting toolbar title to ", title);
        if (title == null) {
            title = getString(R.string.app_name);
        }
        setTitle(title);
    }

    /**
     * 获取媒体浏览Fragment
     *
     * @return
     */
    private MediaBrowserFragment getBrowseFragment() {
        return (MediaBrowserFragment) getFragmentManager().findFragmentByTag(FRAGMENT_TAG);
    }

    /**
     * 获取当前媒体数据id
     *
     * @return 媒体数据id
     */
    private String getMediaId() {
        MediaBrowserFragment fragment = getBrowseFragment();
        if (fragment == null) {
            return null;
        }
        return fragment.getMediaId();
    }

    @Override
    protected void onConnected() {
        if (mVoiceSearchParams != null) {
            // If there is a bootstrap parameter to start from a search query, we
            // send it to the media session and set it to null, so it won't play again
            // when the activity is stopped/started or recreated:
            String query = mVoiceSearchParams.getString(SearchManager.QUERY);
            MediaControllerCompat.getMediaController(MusicPlayerActivity.this).getTransportControls()
                    .playFromSearch(query, mVoiceSearchParams);
            mVoiceSearchParams = null;
        }
        // 分发到媒体浏览Fragment
        getBrowseFragment().onConnected();
    }
}
