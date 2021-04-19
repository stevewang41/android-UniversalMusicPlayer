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

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.android.uamp.R;
import com.example.android.uamp.utils.MediaIDHelper;

/**
 * 媒体数据列表项ViewHolder
 */
public class MediaItemViewHolder {

    /** 无效状态，作为默认值 */
    public static final int STATE_INVALID = -1;
    /** 发生错误不可播放的状态 */
    public static final int STATE_NONE = 0;
    /** 可播放但未播放的状态， */
    public static final int STATE_PLAYABLE = 1;
    /** 暂停状态 */
    public static final int STATE_PAUSED = 2;
    /** 正在播放状态 */
    public static final int STATE_PLAYING = 3;

    private static ColorStateList sColorStatePlaying;
    private static ColorStateList sColorStateNotPlaying;

    /** 均衡器图标 */
    private ImageView mImageView;
    /** item标题 */
    private TextView mTitleView;
    /** item描述 */
    private TextView mDescriptionView;

    /**
     * 组建ListView
     *
     * @param activity    Activity
     * @param convertView The old view to reuse, if possible.
     * @param parent      The parent that this view will eventually be attached to
     * @param item        媒体数据
     * @return a view for use in media item list.
     */
    static View setupListView(Activity activity, View convertView, ViewGroup parent, MediaBrowserCompat.MediaItem item) {
        if (sColorStateNotPlaying == null || sColorStatePlaying == null) {
            initializeColorStateLists(activity);
        }

        MediaItemViewHolder holder;
        Integer cachedState = STATE_INVALID;

        if (convertView == null) {
            convertView = LayoutInflater.from(activity).inflate(R.layout.media_list_item, parent, false);
            holder = new MediaItemViewHolder();
            holder.mImageView = (ImageView) convertView.findViewById(R.id.play_eq);
            holder.mTitleView = (TextView) convertView.findViewById(R.id.title);
            holder.mDescriptionView = (TextView) convertView.findViewById(R.id.description);
            convertView.setTag(holder);
        } else {
            holder = (MediaItemViewHolder) convertView.getTag();
            cachedState = (Integer) convertView.getTag(R.id.tag_mediaitem_state_cache);
        }

        MediaDescriptionCompat description = item.getDescription();
        holder.mTitleView.setText(description.getTitle());
        holder.mDescriptionView.setText(description.getSubtitle());

        // If the state of convertView is different, we need to adapt the view to the new state.
        int state = getMediaItemState(activity, item);
        if (cachedState == null || cachedState != state) {
            Drawable drawable = getDrawableByState(activity, state);
            if (drawable != null) {
                holder.mImageView.setImageDrawable(drawable);
                holder.mImageView.setVisibility(View.VISIBLE);
            } else {
                holder.mImageView.setVisibility(View.GONE);
            }
            convertView.setTag(R.id.tag_mediaitem_state_cache, state);
        }

        return convertView;
    }

    private static void initializeColorStateLists(Context ctx) {
        sColorStateNotPlaying = ColorStateList.valueOf(ctx.getResources().getColor(
                R.color.media_item_icon_not_playing));
        sColorStatePlaying = ColorStateList.valueOf(ctx.getResources().getColor(
                R.color.media_item_icon_playing));
    }

    /**
     * 根据播放状态获取Drawable
     *
     * @param context 上下文
     * @param state   播放状态
     * @return
     */
    public static Drawable getDrawableByState(Context context, int state) {
        if (sColorStateNotPlaying == null || sColorStatePlaying == null) {
            initializeColorStateLists(context);
        }
        switch (state) {
            case STATE_PLAYABLE:
                Drawable pauseDrawable = ContextCompat.getDrawable(context, R.drawable.ic_play_arrow_black_36dp);
                DrawableCompat.setTintList(pauseDrawable, sColorStateNotPlaying);
                return pauseDrawable;
            case STATE_PLAYING:
                // 均衡器帧动画
                AnimationDrawable animation = (AnimationDrawable)
                        ContextCompat.getDrawable(context, R.drawable.ic_equalizer_white_36dp);
                DrawableCompat.setTintList(animation, sColorStatePlaying);
                animation.start();
                return animation;
            case STATE_PAUSED:
                // 均衡器帧动画第一帧图片
                Drawable playDrawable = ContextCompat.getDrawable(context, R.drawable.ic_equalizer1_white_36dp);
                DrawableCompat.setTintList(playDrawable, sColorStatePlaying);
                return playDrawable;
            default:
                return null;
        }
    }

    /**
     * 获取某个媒体项播放状态
     *
     * @param context   上下文
     * @param mediaItem 媒体项
     * @return
     */
    public static int getMediaItemState(Activity context, MediaBrowserCompat.MediaItem mediaItem) {
        int state = STATE_NONE;
        // Set state to playable first, then override to playing or paused state if needed
        if (mediaItem.isPlayable()) {
            state = STATE_PLAYABLE;
            if (MediaIDHelper.isMediaItemPlaying(context, mediaItem)) {
                state = getStateFromController(context);
            }
        }
        return state;
    }

    /**
     * 从 MediaController 中获取播放状态，即完成 PlaybackState 到 ViewHolderState 的映射
     *
     * @param context 上下文
     * @return
     */
    public static int getStateFromController(Activity context) {
        MediaControllerCompat controller = MediaControllerCompat.getMediaController(context);
        PlaybackStateCompat playbackState = controller.getPlaybackState();
        if (playbackState == null || playbackState.getState() == PlaybackStateCompat.STATE_ERROR) {
            return MediaItemViewHolder.STATE_NONE;
        } else if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            return MediaItemViewHolder.STATE_PLAYING;
        } else {
            return MediaItemViewHolder.STATE_PAUSED;
        }
    }
}
