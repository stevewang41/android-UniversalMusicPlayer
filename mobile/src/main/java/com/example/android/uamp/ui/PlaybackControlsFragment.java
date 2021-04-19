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

import android.app.Fragment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.android.uamp.AlbumArtCache;
import com.example.android.uamp.MusicService;
import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;

/**
 * {@link MusicPlayerActivity} 和 {@link PlaceholderActivity} 下方的播放控制条Fragment
 */
public class PlaybackControlsFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = LogHelper.makeLogTag(PlaybackControlsFragment.class);

    /** 头像 */
    private ImageView mAlbumArt;
    /** 标题 */
    private TextView mTitle;
    /** 副标题 */
    private TextView mSubtitle;
    /** 额外信息 */
    private TextView mExtraInfo;
    /** 播放/暂停按钮 */
    private ImageButton mPlayPause;
    /** 头像Url */
    private String mArtUrl;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_playback_controls, container, false);
        mAlbumArt = rootView.findViewById(R.id.album_art);
        mTitle = rootView.findViewById(R.id.title);
        mSubtitle = rootView.findViewById(R.id.artist);
        mExtraInfo = rootView.findViewById(R.id.extra_info);
        mPlayPause = rootView.findViewById(R.id.play_pause);
        mPlayPause.setEnabled(true);
        mPlayPause.setOnClickListener(this);
        rootView.setOnClickListener(this);
        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
        LogHelper.d(TAG, "fragment.onStart");
        MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
        if (controller != null) {
            onConnected();
        }
    }

    /**
     * MediaBrowser成功连接到MediaBrowserService
     */
    public void onConnected() {
        MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
        LogHelper.d(TAG, "onConnected, mediaController==null? ", controller == null);
        if (controller != null) {
            updateMetadata(controller.getMetadata());
            updatePlaybackState(controller.getPlaybackState());
            controller.registerCallback(mControllerCallback);
        }
    }


    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.play_pause) {
            MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
            PlaybackStateCompat stateObj = controller.getPlaybackState();
            final int state = stateObj == null ? PlaybackStateCompat.STATE_NONE : stateObj.getState();
            LogHelper.d(TAG, "Play button pressed, in state " + state);
            if (state == PlaybackStateCompat.STATE_PAUSED ||
                    state == PlaybackStateCompat.STATE_STOPPED ||
                    state == PlaybackStateCompat.STATE_NONE) {
                controller.getTransportControls().play();
            } else if (state == PlaybackStateCompat.STATE_PLAYING ||
                    state == PlaybackStateCompat.STATE_BUFFERING ||
                    state == PlaybackStateCompat.STATE_CONNECTING) {
                controller.getTransportControls().pause();
            }
        } else {
            Intent intent = new Intent(getActivity(), FullScreenPlayerActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
            MediaMetadataCompat metadata = controller.getMetadata();
            if (metadata != null) {
                intent.putExtra(MusicPlayerActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION, metadata.getDescription());
            }
            startActivity(intent);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        LogHelper.d(TAG, "fragment.onStop");
        MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
        if (controller != null) {
            controller.unregisterCallback(mControllerCallback);
        }
    }

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    private final MediaControllerCompat.Callback mControllerCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            LogHelper.d(TAG, "Received playback state change to state ", state.getState());
            PlaybackControlsFragment.this.updatePlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            if (metadata == null) {
                return;
            }
            LogHelper.d(TAG, "Received metadata state change to mediaId=",
                    metadata.getDescription().getMediaId(),
                    " song=", metadata.getDescription().getTitle());
            PlaybackControlsFragment.this.updateMetadata(metadata);
        }
    };

    /**
     * 更新播放状态
     *
     * @param state The new playback state of the session
     */
    private void updatePlaybackState(PlaybackStateCompat state) {
        LogHelper.d(TAG, "onPlaybackStateChanged ", state);
        if (getActivity() == null) {
            LogHelper.w(TAG, "onPlaybackStateChanged called when getActivity null," +
                    "this should not happen if the callback was properly unregistered. Ignoring.");
            return;
        }
        if (state == null) {
            return;
        }
        boolean enablePlay = false;
        switch (state.getState()) {
            case PlaybackStateCompat.STATE_PAUSED:
            case PlaybackStateCompat.STATE_STOPPED:
                enablePlay = true;
                break;
            case PlaybackStateCompat.STATE_ERROR:
                LogHelper.e(TAG, "error playbackstate: ", state.getErrorMessage());
                Toast.makeText(getActivity(), state.getErrorMessage(), Toast.LENGTH_LONG).show();
                break;
        }

        if (enablePlay) {
            mPlayPause.setImageDrawable(
                    ContextCompat.getDrawable(getActivity(), R.drawable.ic_play_arrow_black_36dp));
        } else {
            mPlayPause.setImageDrawable(
                    ContextCompat.getDrawable(getActivity(), R.drawable.ic_pause_black_36dp));
        }

        MediaControllerCompat controller = MediaControllerCompat.getMediaController(getActivity());
        String extraInfo = null;
        if (controller != null && controller.getExtras() != null) {
            String castName = controller.getExtras().getString(MusicService.EXTRA_CONNECTED_CAST);
            if (castName != null) {
                extraInfo = getResources().getString(R.string.casting_to_device, castName);
            }
        }
        if (extraInfo == null) {
            mExtraInfo.setVisibility(View.GONE);
        } else {
            mExtraInfo.setText(extraInfo);
            mExtraInfo.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 更新播放数据
     *
     * @param metadata The current metadata for the session or null if none.
     */
    private void updateMetadata(MediaMetadataCompat metadata) {
        LogHelper.d(TAG, "onMetadataChanged ", metadata);
        if (getActivity() == null) {
            LogHelper.w(TAG, "onMetadataChanged called when getActivity null," +
                    "this should not happen if the callback was properly unregistered. Ignoring.");
            return;
        }
        if (metadata == null) {
            return;
        }

        mTitle.setText(metadata.getDescription().getTitle());
        mSubtitle.setText(metadata.getDescription().getSubtitle());
        String artUrl = null;
        if (metadata.getDescription().getIconUri() != null) {
            artUrl = metadata.getDescription().getIconUri().toString();
        }
        if (!TextUtils.equals(artUrl, mArtUrl)) {
            mArtUrl = artUrl;
            Bitmap art = metadata.getDescription().getIconBitmap();
            AlbumArtCache cache = AlbumArtCache.getInstance();
            if (art == null) {
                art = cache.getIconImage(mArtUrl);
            }
            if (art != null) {
                mAlbumArt.setImageBitmap(art);
            } else {
                cache.fetch(artUrl, new AlbumArtCache.FetchListener() {
                            @Override
                            public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                                if (icon != null) {
                                    LogHelper.d(TAG, "album art icon of w=", icon.getWidth(),
                                            " h=", icon.getHeight());
                                    if (isAdded()) {
                                        mAlbumArt.setImageBitmap(icon);
                                    }
                                }
                            }
                        }
                );
            }
        }
    }
}
