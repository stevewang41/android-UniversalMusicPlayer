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

package com.example.android.uamp.model;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_GENRE;
import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.example.android.uamp.utils.MediaIDHelper.createMediaID;

/**
 * 歌曲数据提供者，实际的数据源被代理到构造方法参数中的 {@link MusicProviderSource}
 * <p>
 * <p>
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * {@link MusicProviderSource} defined by a constructor argument of this class.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    /** 数据源 */
    private final MusicProviderSource mSource;
    /** 按musicId索引歌曲的容器 */
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;
    /** 按类型存放歌曲的容器 */
    private ConcurrentMap<String, List<MediaMetadataCompat>> mMusicListsByGenre;
    /** 存放用户"喜欢"的歌曲容器，value为歌曲唯一id */
    private final Set<String> mFavoriteTracks;
    /** 当前数据状态 */
    private volatile State mCurrentState = State.NON_INITIALIZED;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    /**
     * 歌曲数据获取回调
     */
    public interface Callback {

        void onMusicCatalogReady(boolean success);
    }

    /**
     * 外部实际调用的构造方法，即 {@link #mSource} 为 {@link RemoteJSONSource}
     */
    public MusicProvider() {
        this(new RemoteJSONSource());
    }

    public MusicProvider(MusicProviderSource source) {
        mSource = source;
        mMusicListById = new ConcurrentHashMap<>();
        mMusicListsByGenre = new ConcurrentHashMap<>();
        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    }

    /**
     * Get the list of music tracks from a server and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final Callback callback) {
        LogHelper.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            if (callback != null) {
                // Nothing to do, execute callback immediately
                callback.onMusicCatalogReady(true);
            }
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveMedia();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    /**
     * 获取歌曲数据
     */
    private synchronized void retrieveMedia() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                Iterator<MediaMetadataCompat> tracks = mSource.iterator();
                while (tracks.hasNext()) {
                    MediaMetadataCompat item = tracks.next();
                    String musicId = item.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
                    mMusicListById.put(musicId, new MutableMediaMetadata(musicId, item));
                }
                buildMusicListsByGenre();
                mCurrentState = State.INITIALIZED;
            }
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }

    /**
     * 将 {@link #mMusicListById} 中的歌曲按类型进行划分，并存至 {@link #mMusicListsByGenre}，使客户端可以按音频类型选择播放队列
     */
    private synchronized void buildMusicListsByGenre() {
        ConcurrentMap<String, List<MediaMetadataCompat>> newMusicListsByGenre = new ConcurrentHashMap<>();

        for (MutableMediaMetadata m : mMusicListById.values()) {
            String genre = m.metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE);
            List<MediaMetadataCompat> musicList = newMusicListsByGenre.get(genre);
            if (musicList == null) {
                musicList = new ArrayList<>();
                newMusicListsByGenre.put(genre, musicList);
            }
            musicList.add(m.metadata);
        }
        mMusicListsByGenre = newMusicListsByGenre;
    }

    /**
     * 获取指定mediaId下的子数据
     *
     * @param resources 应用资源
     * @param mediaId   媒体数据id
     * @return
     */
    public List<MediaBrowserCompat.MediaItem> getChildren(Resources resources, String mediaId) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        if (!MediaIDHelper.isBrowseable(mediaId)) {
            return mediaItems;
        }
        if (MEDIA_ID_ROOT.equals(mediaId)) {
            mediaItems.add(createBrowsableMediaItemForRoot(resources));
        } else if (MEDIA_ID_MUSICS_BY_GENRE.equals(mediaId)) {
            for (String genre : getGenres()) {
                mediaItems.add(createBrowsableMediaItemForGenre(resources, genre));
            }
        } else if (mediaId.startsWith(MEDIA_ID_MUSICS_BY_GENRE)) {
            String genre = MediaIDHelper.getHierarchy(mediaId)[1];
            for (MediaMetadataCompat metadata : getMusicsByGenre(genre)) {
                mediaItems.add(createPlayableMediaItem(metadata));
            }
        } else {
            LogHelper.w(TAG, "Skipping unmatched mediaId: ", mediaId);
        }
        return mediaItems;
    }

    /**
     * 构造根目录下可浏览的子数据
     *
     * @param resources 应用资源
     * @return
     */
    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForRoot(Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_MUSICS_BY_GENRE)   // media = "__BY_GENRE__"
                .setTitle(resources.getString(R.string.browse_genres))
                .setSubtitle(resources.getString(R.string.browse_genre_subtitle))
                .setIconUri(Uri.parse("android.resource://" + "com.example.android.uamp/drawable/ic_by_genre"))
                .build();
        return new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    /**
     * 构造分类目录下可浏览的子数据
     *
     * @param resources 应用资源
     * @param genre     歌曲类型
     * @return
     */
    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForGenre(Resources resources, String genre) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_MUSICS_BY_GENRE, genre)) // media = "__BY_GENRE__/genre"
                .setTitle(genre)
                .setSubtitle(resources.getString(R.string.browse_musics_by_genre_subtitle, genre))
                .build();
        return new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    /**
     * 构造可播放的媒体数据
     *
     * @param metadata
     * @return
     */
    private MediaBrowserCompat.MediaItem createPlayableMediaItem(MediaMetadataCompat metadata) {
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)
        String musicId = metadata.getDescription().getMediaId();
        String genre = metadata.getString(MediaMetadataCompat.METADATA_KEY_GENRE);
        String hierarchyAwareMediaID = MediaIDHelper.createMediaID(musicId, MEDIA_ID_MUSICS_BY_GENRE, genre);
        MediaMetadataCompat copy = new MediaMetadataCompat.Builder(metadata)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .build();
        return new MediaBrowserCompat.MediaItem(copy.getDescription(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);

    }

    /**
     * 获取所有歌曲类型
     *
     * @return genres
     */
    public Iterable<String> getGenres() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mMusicListsByGenre.keySet();
    }

    /**
     * Return the MediaMetadataCompat for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public MediaMetadataCompat getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId).metadata : null;
    }

    /**
     * 获取指定类型的所有歌曲
     *
     * @param genre 歌曲类型
     * @return
     */
    public List<MediaMetadataCompat> getMusicsByGenre(String genre) {
        if (mCurrentState != State.INITIALIZED || !mMusicListsByGenre.containsKey(genre)) {
            return Collections.emptyList();
        }
        return mMusicListsByGenre.get(genre);
    }

    /**
     * 获取乱序的歌曲合集
     *
     * @return 歌曲合集
     */
    public Iterable<MediaMetadataCompat> getShuffledMusic() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        List<MediaMetadataCompat> shuffled = new ArrayList<>(mMusicListById.size());
        for (MutableMediaMetadata mutableMetadata : mMusicListById.values()) {
            shuffled.add(mutableMetadata.metadata);
        }
        Collections.shuffle(shuffled);  // 打乱列表的顺序，以支持乱序播放
        return shuffled;
    }

    /**
     * Very basic implementation of a search that filter music tracks with album containing
     * the given query.
     */
    public List<MediaMetadataCompat> searchMusicByAlbum(String album) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
    }

    /**
     * Very basic implementation of a search that filter music tracks with artist containing
     * the given query.
     */
    public List<MediaMetadataCompat> searchMusicByArtist(String artist) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
    }

    /**
     * Very basic implementation of a search that filter music tracks with title containing
     * the given query.
     */
    public List<MediaMetadataCompat> searchMusicBySongTitle(String title) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_TITLE, title);
    }

    /**
     * Very basic implementation of a search that filter music tracks with a genre containing
     * the given query.
     */
    public List<MediaMetadataCompat> searchMusicByGenre(String genre) {
        return searchMusic(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
    }

    private List<MediaMetadataCompat> searchMusic(String metadataField, String query) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadataCompat> result = new ArrayList<>();
        query = query.toLowerCase(Locale.US);
        for (MutableMediaMetadata track : mMusicListById.values()) {
            if (track.metadata.getString(metadataField).toLowerCase(Locale.US).contains(query)) {
                result.add(track.metadata);
            }
        }
        return result;
    }

    /**
     * 更新指定歌曲的关联的图片
     *
     * @param musicId  歌曲唯一id
     * @param albumArt 高分辨大图
     * @param icon     icon小图
     */
    public synchronized void updateMusicArt(String musicId, Bitmap albumArt, Bitmap icon) {
        MediaMetadataCompat metadata = getMusic(musicId);
        metadata = new MediaMetadataCompat.Builder(metadata)
                // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is used, for
                // example, on the lockscreen background when the media session is active.
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                // set small version of the album art in the DISPLAY_ICON. This is used on
                // the MediaDescription and thus it should be small to be serialized if necessary
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
                .build();

        MutableMediaMetadata mutableMetadata = mMusicListById.get(musicId);
        if (mutableMetadata == null) {
            throw new IllegalStateException("Unexpected error: Inconsistent data structures in MusicProvider");
        }
        mutableMetadata.metadata = metadata;
    }

    /**
     * 添加/取消 "喜欢"
     *
     * @param musicId  歌曲唯一id
     * @param favorite 是否"喜欢"
     */
    public void setFavorite(String musicId, boolean favorite) {
        if (favorite) {
            mFavoriteTracks.add(musicId);
        } else {
            mFavoriteTracks.remove(musicId);
        }
    }

    /**
     * 判断歌曲是否在"喜欢"列表中
     *
     * @param musicId 歌曲唯一id
     * @return
     */
    public boolean isFavorite(String musicId) {
        return mFavoriteTracks.contains(musicId);
    }

    /**
     * 歌曲库是否已准备好
     *
     * @return
     */
    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

}
