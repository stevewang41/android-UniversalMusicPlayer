package com.example.android.uamp.ui;

import android.support.v4.media.MediaBrowserCompat;

/**
 * {@link MediaBrowserFragment} 与 {@link MusicPlayerActivity} 通信接口，由 {@link MusicPlayerActivity} 去实现
 */
public interface MediaFragmentListener extends MediaBrowserProvider {

    /**
     * 某个媒体项被选中
     *
     * @param item 被选中的媒体项
     */
    void onMediaItemSelected(MediaBrowserCompat.MediaItem item);

    /**
     * 设置顶Bar标题文案
     *
     * @param title 标题文案
     */
    void setToolbarTitle(CharSequence title);
}