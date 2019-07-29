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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import com.example.android.uamp.MusicService;
import com.example.android.uamp.model.MusicProvider;
import com.example.android.uamp.model.MusicProviderSource;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import static android.support.v4.media.session.MediaSessionCompat.QueueItem;
import static com.google.android.exoplayer2.C.CONTENT_TYPE_MUSIC;
import static com.google.android.exoplayer2.C.USAGE_MEDIA;

/**
 * A class that implements local media playback using {@link
 * com.google.android.exoplayer2.ExoPlayer}
 */
public final class LocalPlayback implements Playback {

    private static final String TAG = LogHelper.makeLogTag(LocalPlayback.class);

    /** 当失去音频焦点，且不需要停止播放，只需要减小音量时，设置的音量大小 */
    private static final float VOLUME_DUCK = 0.2f;
    /** 当获取到音频焦点，设置的音量大小 */
    private static final float VOLUME_NORMAL = 1.0f;

    /** 没有获取到音频焦点，也不允许duck状态 */
    private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
    /** 没有获取到音频焦点，但允许duck状态 */
    private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
    /** 获取到音频焦点 */
    private static final int AUDIO_FOCUSED = 2;
    /** 当前音频焦点的状态 */
    private int mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;

    private final Context mContext;
    private final WifiManager.WifiLock mWifiLock;
    private boolean mPlayOnFocusGain;
    private Callback mCallback;
    private final MusicProvider mMusicProvider;
    private String mCurrentMediaId;


    private final AudioManager mAudioManager;
    private SimpleExoPlayer mExoPlayer;
    private final ExoPlayerEventListener mEventListener = new ExoPlayerEventListener();

    // Whether to return STATE_NONE or STATE_STOPPED when mExoPlayer is null;
    private boolean mExoPlayerNullIsStopped =  false;

    /** 标识 {@link #mAudioNoisyReceiver} 是否已被注册 */
    private boolean mAudioNoisyReceiverRegistered;
    /** 用来注册 {@link #mAudioNoisyReceiver} 的IntentFilter */
    private final IntentFilter mAudioNoisyIntentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    /**
     * 接收 有线耳机拔出 或 无线耳机断开 事件的广播接收者
     */
    private final BroadcastReceiver mAudioNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                LogHelper.d(TAG, "Headphones disconnected.");
                // 当音乐正在播放中，通知Service暂停播放音乐（在Service.onStartCommand中处理此命令）
                if (isPlaying()) {
                    Intent i = new Intent(context, MusicService.class);
                    i.setAction(MusicService.ACTION_CMD);
                    i.putExtra(MusicService.CMD_NAME, MusicService.CMD_PAUSE);
                    mContext.startService(i);
                }
            }
        }
    };

    public LocalPlayback(Context context, MusicProvider musicProvider) {
        Context applicationContext = context.getApplicationContext();
        this.mContext = applicationContext;
        this.mMusicProvider = musicProvider;

        this.mAudioManager =
                (AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);
        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        this.mWifiLock =
                ((WifiManager) applicationContext.getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(WifiManager.WIFI_MODE_FULL, "uAmp_lock");
    }

    @Override
    public void start() {
        // Nothing to do
    }

    @Override
    public void stop(boolean notifyListeners) {
        giveUpAudioFocus();
        unregisterAudioNoisyReceiver();
        releaseResources(true);
    }

    @Override
    public void setState(int state) {
        // Nothing to do (mExoPlayer holds its own state).
    }

    @Override
    public int getState() {
        if (mExoPlayer == null) {
            return mExoPlayerNullIsStopped
                    ? PlaybackStateCompat.STATE_STOPPED
                    : PlaybackStateCompat.STATE_NONE;
        }
        switch (mExoPlayer.getPlaybackState()) {
            case Player.STATE_IDLE:
                return PlaybackStateCompat.STATE_PAUSED;
            case Player.STATE_BUFFERING:
                return PlaybackStateCompat.STATE_BUFFERING;
            case Player.STATE_READY:
                return mExoPlayer.getPlayWhenReady()
                        ? PlaybackStateCompat.STATE_PLAYING
                        : PlaybackStateCompat.STATE_PAUSED;
            case Player.STATE_ENDED:
                return PlaybackStateCompat.STATE_PAUSED;
            default:
                return PlaybackStateCompat.STATE_NONE;
        }
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isPlaying() {
        return mPlayOnFocusGain || (mExoPlayer != null && mExoPlayer.getPlayWhenReady());
    }

    @Override
    public long getCurrentStreamPosition() {
        return mExoPlayer != null ? mExoPlayer.getCurrentPosition() : 0;
    }

    @Override
    public void updateLastKnownStreamPosition() {
        // Nothing to do. Position maintained by ExoPlayer.
    }

    @Override
    public void play(QueueItem item) {
        mPlayOnFocusGain = true;
        tryToGetAudioFocus();
        registerAudioNoisyReceiver();
        String mediaId = item.getDescription().getMediaId();
        boolean mediaHasChanged = !TextUtils.equals(mediaId, mCurrentMediaId);
        if (mediaHasChanged) {
            mCurrentMediaId = mediaId;
        }

        if (mediaHasChanged || mExoPlayer == null) {
            releaseResources(false); // release everything except the player
            MediaMetadataCompat track = mMusicProvider.getMusic(MediaIDHelper.extractMusicIDFromMediaID(mediaId));

            String source = track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE);
            if (source != null) {
                source = source.replaceAll(" ", "%20"); // Escape spaces for URLs
            }

            if (mExoPlayer == null) {
                mExoPlayer = ExoPlayerFactory.newSimpleInstance(
                        new DefaultRenderersFactory(mContext),
                        new DefaultTrackSelector(),
                        new DefaultLoadControl());
                mExoPlayer.addListener(mEventListener);
            }

            // Android "O" makes much greater use of AudioAttributes, especially
            // with regards to AudioFocus. All of UAMP's tracks are music, but
            // if your content includes spoken word such as audiobooks or podcasts
            // then the content type should be set to CONTENT_TYPE_SPEECH for those
            // tracks.
            final AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(CONTENT_TYPE_MUSIC)
                    .setUsage(USAGE_MEDIA)
                    .build();
            mExoPlayer.setAudioAttributes(audioAttributes);

            // Produces DataSource instances through which media data is loaded.
            DataSource.Factory dataSourceFactory =
                    new DefaultDataSourceFactory(
                            mContext, Util.getUserAgent(mContext, "uamp"), null);
            // Produces Extractor instances for parsing the media data.
            ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
            // The MediaSource represents the media to be played.
            ExtractorMediaSource.Factory extractorMediaFactory =
                    new ExtractorMediaSource.Factory(dataSourceFactory);
            extractorMediaFactory.setExtractorsFactory(extractorsFactory);
            MediaSource mediaSource = extractorMediaFactory.createMediaSource(Uri.parse(source));

            // Prepares media to play (happens on background thread) and triggers
            // {@code onPlayerStateChanged} callback when the stream is ready to play.
            mExoPlayer.prepare(mediaSource);

            // If we are streaming from the internet, we want to hold a
            // Wifi lock, which prevents the Wifi radio from going to
            // sleep while the song is playing.
            mWifiLock.acquire();
        }

        configurePlayerState();
    }

    @Override
    public void pause() {
        // Pause player and cancel the 'foreground service' state.
        if (mExoPlayer != null) {
            mExoPlayer.setPlayWhenReady(false);
        }
        // While paused, retain the player instance, but give up audio focus.
        releaseResources(false);
        unregisterAudioNoisyReceiver();
    }

    @Override
    public void seekTo(long position) {
        LogHelper.d(TAG, "seekTo called with ", position);
        if (mExoPlayer != null) {
            registerAudioNoisyReceiver();
            mExoPlayer.seekTo(position);
        }
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public void setCurrentMediaId(String mediaId) {
        this.mCurrentMediaId = mediaId;
    }

    @Override
    public String getCurrentMediaId() {
        return mCurrentMediaId;
    }

    /**
     * 尝试获取音频焦点
     * {@link AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener l, int streamType, int durationHint)}
     * OnAudioFocusChangeListener l：音频焦点状态改变监听器
     * int streamType：请求焦点的音频类型
     * int durationHint：请求焦点音频持续性的指示
     *      {@link AudioManager#AUDIOFOCUS_GAIN}：指示申请得到的音频焦点不知道会持续多久，一般是长期占有
     *      {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT}：指示要申请的音频焦点是暂时性的，很快就会释放
     *      {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}：指示要申请的音频焦点是暂时性的，并且当前正在使用焦点的音频可以继续播放，
     *                                                               只是要“duck”一下（降低音量）
     */
    private void tryToGetAudioFocus() {
        LogHelper.d(TAG, "tryToGetAudioFocus");
        int result = mAudioManager.requestAudioFocus(mOnAudioFocusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentAudioFocusState = AUDIO_FOCUSED;
        } else {
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
        }
    }

    /**
     * 释放音频焦点
     */
    private void giveUpAudioFocus() {
        LogHelper.d(TAG, "giveUpAudioFocus");
        if (mAudioManager.abandonAudioFocus(mOnAudioFocusChangeListener) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
        }
    }

    /**
     * 根据音频焦点的设置重新配置播放器 以及 启动/重新启动 播放器。调用这个方法 启动/重新启动 播放器实例取决于当前音频焦点的状态。
     * 因此如果我们持有音频焦点，则正常播放音频；如果我们失去音频焦点，播放器将暂停播放或者设置为低音量，这取决于当前焦点设置允许哪种设置
     */
    private void configurePlayerState() {
        LogHelper.d(TAG, "configurePlayerState. mCurrentAudioFocusState=", mCurrentAudioFocusState);
        if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_NO_DUCK) {
            // We don't have audio focus and can't duck, so we have to pause
            pause();
        } else {
            registerAudioNoisyReceiver();

            if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_CAN_DUCK) {
                // We're permitted to play, but only if we 'duck', ie: play softly
                mExoPlayer.setVolume(VOLUME_DUCK);
            } else {
                mExoPlayer.setVolume(VOLUME_NORMAL);
            }

            // If we were playing when we lost focus, we need to resume playing.
            if (mPlayOnFocusGain) {
                // 播放过程中因失去音频焦点而暂停播放，短时间内需要恢复播放时会进入这里
                mExoPlayer.setPlayWhenReady(true);
                mPlayOnFocusGain = false;
            }
        }
    }

    /**
     * 调用{@link AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener, int, int)} 请求音频焦点之后
     * 负责监听音频焦点状态的Listener
     */
    private final AudioManager.OnAudioFocusChangeListener mOnAudioFocusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    LogHelper.d(TAG, "onAudioFocusChange. focusChange=", focusChange);
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            mCurrentAudioFocusState = AUDIO_FOCUSED;
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Audio focus was lost, but it's possible to duck (i.e.: play quietly)
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_CAN_DUCK;
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Lost audio focus, but will gain it back (shortly), so note whether playback should resume
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
                            mPlayOnFocusGain = mExoPlayer != null && mExoPlayer.getPlayWhenReady();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Lost audio focus, probably "permanently"
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
                            break;
                    }

                    if (mExoPlayer != null) {
                        // Update the player state based on the change
                        configurePlayerState();
                    }
                }
            };

    /**
     * Releases resources used by the service for playback, which is mostly just the WiFi lock for
     * local playback. If requested, the ExoPlayer instance is also released.
     *
     * @param releasePlayer Indicates whether the player should also be released
     */
    private void releaseResources(boolean releasePlayer) {
        LogHelper.d(TAG, "releaseResources. releasePlayer=", releasePlayer);

        // Stops and releases player (if requested and available).
        if (releasePlayer && mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer.removeListener(mEventListener);
            mExoPlayer = null;
            mExoPlayerNullIsStopped = true;
            mPlayOnFocusGain = false;
        }

        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        }
    }

    /**
     * 注册耳机插拔、蓝牙耳机断连的广播接收者
     */
    private void registerAudioNoisyReceiver() {
        if (!mAudioNoisyReceiverRegistered) {
            mContext.registerReceiver(mAudioNoisyReceiver, mAudioNoisyIntentFilter);
            mAudioNoisyReceiverRegistered = true;
        }
    }

    /**
     * 注销耳机插拔、蓝牙耳机断连的广播接收者
     * 为了避免内存泄露，在暂停或停止播放时完成注销
     */
    private void unregisterAudioNoisyReceiver() {
        if (mAudioNoisyReceiverRegistered) {
            mContext.unregisterReceiver(mAudioNoisyReceiver);
            mAudioNoisyReceiverRegistered = false;
        }
    }

    private final class ExoPlayerEventListener implements Player.EventListener {
        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
            // Nothing to do.
        }

        @Override
        public void onTracksChanged(
                TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            // Nothing to do.
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
            // Nothing to do.
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            switch (playbackState) {
                case Player.STATE_IDLE:
                case Player.STATE_BUFFERING:
                case Player.STATE_READY:
                    if (mCallback != null) {
                        mCallback.onPlaybackStatusChanged(getState());
                    }
                    break;
                case Player.STATE_ENDED:
                    // The media player finished playing the current song.
                    if (mCallback != null) {
                        mCallback.onCompletion();
                    }
                    break;
            }
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            final String what;
            switch (error.type) {
                case ExoPlaybackException.TYPE_SOURCE:
                    what = error.getSourceException().getMessage();
                    break;
                case ExoPlaybackException.TYPE_RENDERER:
                    what = error.getRendererException().getMessage();
                    break;
                case ExoPlaybackException.TYPE_UNEXPECTED:
                    what = error.getUnexpectedException().getMessage();
                    break;
                default:
                    what = "Unknown: " + error;
            }

            LogHelper.e(TAG, "ExoPlayer error: what=" + what);
            if (mCallback != null) {
                mCallback.onError("ExoPlayer error " + what);
            }
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            // Nothing to do.
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
            // Nothing to do.
        }

        @Override
        public void onSeekProcessed() {
            // Nothing to do.
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            // Nothing to do.
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            // Nothing to do.
        }
    }
}
