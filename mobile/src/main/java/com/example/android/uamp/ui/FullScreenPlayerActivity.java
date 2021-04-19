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

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.example.android.uamp.AlbumArtCache;
import com.example.android.uamp.MusicService;
import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

/**
 * 展示当前播放曲目的全屏播放器Activity，可以控制音乐的播放、暂停、上一曲、下一曲以及进度调节
 */
public class FullScreenPlayerActivity extends ActionBarCastActivity implements View.OnClickListener,
        SeekBar.OnSeekBarChangeListener {

    private static final String TAG = LogHelper.makeLogTag(FullScreenPlayerActivity.class);
    /** 进度更新初始延迟 0.1s */
    private static final long PROGRESS_UPDATE_INITIAL_DELAY = 100;
    /** 进度更新时间间隔 1s */
    private static final long PROGRESS_UPDATE_INTERNAL = 1000;
    /** 背景图 */
    private ImageView mBackgroundImage;
    /** 页面加载loading视图 */
    private ProgressBar mLoading;
    /** 第一行文案：显示标题 */
    private TextView mLine1;
    /** 第二行文案：显示副标题 */
    private TextView mLine2;
    /** 第三行文案：显示额外信息 */
    private TextView mLine3;
    /** 播放控制区 */
    private View mControllers;
    /** 进度条当前时长 */
    private TextView mStart;
    /** 进度条总时长 */
    private TextView mEnd;
    /** 进度条 */
    private SeekBar mSeekBar;
    /** 上一条 */
    private ImageView mSkipPrev;
    /** 下一条 */
    private ImageView mSkipNext;
    /** 播放/暂停 */
    private ImageView mPlayPause;
    /** 暂停图标（播放时展示） */
    private Drawable mPauseDrawable;
    /** 播放图标（未播放时展示） */
    private Drawable mPlayDrawable;
    /** 当前封面图Url，用于校验，避免在弱网环境下封面图请求返回之前已经切换了数据 */
    private String mCurrentArtUrl;
    /** MediaBrowse */
    private MediaBrowserCompat mMediaBrowser;
    /** 记录最近一次播放状态更新的值，包括播放状态、进度、速度等 */
    private PlaybackStateCompat mLastUpdateState;
    /** 用于执行定时任务线程池，内部只有一个线程，可以确保任务的执行顺序 */
    private final ScheduledExecutorService mExecutorService = Executors.newSingleThreadScheduledExecutor();
    /** 用来取消定时更新进度的任务 */
    private ScheduledFuture<?> mScheduleFuture;
    /** 主线程Handler，用来更新进度 */
    private final Handler mHandler = new Handler();
    /** 进度更新任务 */
    private final Runnable mUpdateProgressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_player);
        initializeToolbar();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("");
        }

        mBackgroundImage = (ImageView) findViewById(R.id.background_image);
        mLoading = (ProgressBar) findViewById(R.id.progressBar1);
        mLine1 = (TextView) findViewById(R.id.line1);
        mLine2 = (TextView) findViewById(R.id.line2);
        mLine3 = (TextView) findViewById(R.id.line3);
        mControllers = findViewById(R.id.controllers);
        mStart = (TextView) findViewById(R.id.startText);
        mEnd = (TextView) findViewById(R.id.endText);
        mSeekBar = (SeekBar) findViewById(R.id.seekBar1);
        mSkipPrev = (ImageView) findViewById(R.id.prev);
        mSkipNext = (ImageView) findViewById(R.id.next);
        mPlayPause = (ImageView) findViewById(R.id.play_pause);
        mPlayDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_play_arrow_white_48dp);
        mPauseDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_pause_white_48dp);
        mSkipPrev.setOnClickListener(this);
        mSkipNext.setOnClickListener(this);
        mPlayPause.setOnClickListener(this);
        mSeekBar.setOnSeekBarChangeListener(this);

        // Only update from the intent if we are not recreating from a config change:
        if (savedInstanceState == null) {
            updateFromParams(getIntent());
        }
        mMediaBrowser = new MediaBrowserCompat(this,
                new ComponentName(this, MusicService.class), mConnectionCallback, null);
    }

    /**
     * 根据调起参数更新视图
     *
     * @param intent
     */
    private void updateFromParams(Intent intent) {
        if (intent != null) {
            MediaDescriptionCompat description = intent
                    .getParcelableExtra(MusicPlayerActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION);
            if (description != null) {
                updateMediaDescription(description);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mMediaBrowser.connect();
    }

    @Override
    public void onStop() {
        super.onStop();
        MediaControllerCompat mediaController = MediaControllerCompat.getMediaController(this);
        if (mediaController != null) {
            mediaController.unregisterCallback(mControllerCallback);
        }
        mMediaBrowser.disconnect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSeekBarUpdate();
        mExecutorService.shutdown();
    }

    @Override
    public void onClick(View v) {
        int viewId = v.getId();
        if (viewId == R.id.prev) {
            MediaControllerCompat.getMediaController(this).getTransportControls().skipToPrevious();
        } else if (viewId == R.id.next) {
            MediaControllerCompat.getMediaController(this).getTransportControls().skipToNext();
        } else if (viewId == R.id.play_pause) {
            PlaybackStateCompat state = MediaControllerCompat.getMediaController(this).getPlaybackState();
            if (state != null) {
                switch (state.getState()) {
                    case PlaybackStateCompat.STATE_PLAYING: // fall through
                    case PlaybackStateCompat.STATE_BUFFERING:
                        MediaControllerCompat.getMediaController(this).getTransportControls().pause();
                        stopSeekBarUpdate();
                        break;
                    case PlaybackStateCompat.STATE_PAUSED:
                    case PlaybackStateCompat.STATE_STOPPED:
                        MediaControllerCompat.getMediaController(this).getTransportControls().play();
                        scheduleSeekBarUpdate();
                        break;
                    default:
                        LogHelper.d(TAG, "onClick with state ", state.getState());
                }
            }
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        mStart.setText(DateUtils.formatElapsedTime(progress / 1000));
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        stopSeekBarUpdate();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        MediaControllerCompat.getMediaController(FullScreenPlayerActivity.this).getTransportControls()
                .seekTo(seekBar.getProgress());
        scheduleSeekBarUpdate();
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
                        // 连接MediaBrowserService成功时，创建MediaController
                        MediaControllerCompat mediaController = new MediaControllerCompat(
                                FullScreenPlayerActivity.this, mMediaBrowser.getSessionToken());
                        MediaMetadataCompat metadata = mediaController.getMetadata();
                        if (metadata == null) {
                            finish();
                            return;
                        }
                        MediaControllerCompat.setMediaController(FullScreenPlayerActivity.this, mediaController);
                        mediaController.registerCallback(mControllerCallback);

                        PlaybackStateCompat state = mediaController.getPlaybackState();
                        updatePlaybackState(state);
                        updateMediaDescription(metadata.getDescription());
                        updateMediaDuration(metadata);
                        updateProgress();
                        if (state != null && (state.getState() == PlaybackStateCompat.STATE_PLAYING ||
                                state.getState() == PlaybackStateCompat.STATE_BUFFERING)) {
                            scheduleSeekBarUpdate();
                        }
                    } catch (RemoteException e) {
                        LogHelper.e(TAG, e, "could not connect media controller");
                    }
                }
            };

    /**
     * MediaController回调，接收MediaSession的状态、数据变化
     */
    private final MediaControllerCompat.Callback mControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            LogHelper.d(TAG, "onPlaybackstate changed", state);
            updatePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata != null) {
                updateMediaDescription(metadata.getDescription());
                updateMediaDuration(metadata);
            }
        }
    };

    /**
     * 更新播放状态
     *
     * @param state The new playback state of the session
     */
    private void updatePlaybackState(PlaybackStateCompat state) {
        if (state == null) {
            return;
        }
        mLastUpdateState = state;
        MediaControllerCompat MediaController = MediaControllerCompat.getMediaController(this);
        if (MediaController != null && MediaController.getExtras() != null) {
            String castName = MediaController.getExtras().getString(MusicService.EXTRA_CONNECTED_CAST);
            String line3Text = castName == null ? "" : getResources().getString(R.string.casting_to_device, castName);
            mLine3.setText(line3Text);
        }

        switch (state.getState()) {
            case PlaybackStateCompat.STATE_PLAYING:
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPauseDrawable);
                mControllers.setVisibility(VISIBLE);
                scheduleSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                mControllers.setVisibility(VISIBLE);
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
                mLoading.setVisibility(INVISIBLE);
                mPlayPause.setVisibility(VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                stopSeekBarUpdate();
                break;
            case PlaybackStateCompat.STATE_BUFFERING:
                mPlayPause.setVisibility(INVISIBLE);
                mLoading.setVisibility(VISIBLE);
                mLine3.setText(R.string.loading);
                stopSeekBarUpdate();
                break;
            default:
                LogHelper.d(TAG, "Unhandled state ", state.getState());
        }

        mSkipNext.setVisibility((state.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) == 0
                ? INVISIBLE : VISIBLE);
        mSkipPrev.setVisibility((state.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) == 0
                ? INVISIBLE : VISIBLE);
    }

    /**
     * 更新媒体描述信息
     *
     * @param description 媒体描述
     */
    private void updateMediaDescription(MediaDescriptionCompat description) {
        if (description == null) {
            return;
        }
        LogHelper.d(TAG, "updateMediaDescription called ");
        mLine1.setText(description.getTitle());
        mLine2.setText(description.getSubtitle());
        if (description.getIconUri() == null) {
            return;
        }
        String artUrl = description.getIconUri().toString();
        mCurrentArtUrl = artUrl;
        Bitmap image = AlbumArtCache.getInstance().getBigImage(artUrl);
        if (image == null) {
            image = description.getIconBitmap();
        }
        if (image != null) {
            // if we have the art cached or from the MediaDescription, use it:
            mBackgroundImage.setImageBitmap(image);
        } else {
            // otherwise, fetch a high res version and update:
            AlbumArtCache.getInstance().fetch(artUrl, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    // sanity check, in case a new fetch request has been done while
                    // the previous hasn't yet returned:
                    if (artUrl.equals(mCurrentArtUrl)) {
                        mBackgroundImage.setImageBitmap(bitmap);
                    }
                }
            });
        }
    }

    /**
     * 更新媒体总时长
     *
     * @param metadata 媒体数据
     */
    private void updateMediaDuration(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }
        LogHelper.d(TAG, "updateDuration called ");
        int duration = (int) metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        mSeekBar.setMax(duration);
        mEnd.setText(DateUtils.formatElapsedTime(duration / 1000));
    }

    /**
     * 启动进度条更新任务
     */
    private void scheduleSeekBarUpdate() {
        stopSeekBarUpdate();
        if (!mExecutorService.isShutdown()) {
            mScheduleFuture = mExecutorService.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    mHandler.post(mUpdateProgressTask);
                }
            }, PROGRESS_UPDATE_INITIAL_DELAY, PROGRESS_UPDATE_INTERNAL, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止进度条更新任务
     */
    private void stopSeekBarUpdate() {
        if (mScheduleFuture != null) {
            mScheduleFuture.cancel(false);
        }
    }

    /**
     * 更新播放进度
     * UI层无法拿到播放器对象，不能直接获取到播放进度，这里的计算比较比较精妙，值得学习
     */
    private void updateProgress() {
        if (mLastUpdateState == null) {
            return;
        }
        long currentPosition = mLastUpdateState.getPosition();
        if (mLastUpdateState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            // Calculate the elapsed time between the last position update and now and unless
            // paused, we can assume (delta * speed) + current position is approximately the
            // latest position. This ensure that we do not repeatedly call the getPlaybackState()
            // on MediaControllerCompat.
            long timeDelta = SystemClock.elapsedRealtime() - mLastUpdateState.getLastPositionUpdateTime();
            currentPosition += (int) timeDelta * mLastUpdateState.getPlaybackSpeed();
        }
        mSeekBar.setProgress((int) currentPosition);
    }
}
