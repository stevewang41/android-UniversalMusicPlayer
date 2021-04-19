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

 package com.example.android.uamp;

 import android.app.PendingIntent;
 import android.content.BroadcastReceiver;
 import android.content.Context;
 import android.content.Intent;
 import android.content.IntentFilter;
 import android.os.Bundle;
 import android.os.Handler;
 import android.os.Message;
 import android.os.RemoteException;
 import android.support.annotation.NonNull;
 import android.support.v4.media.MediaBrowserCompat.MediaItem;
 import android.support.v4.media.MediaBrowserServiceCompat;
 import android.support.v4.media.MediaMetadataCompat;
 import android.support.v4.media.session.MediaButtonReceiver;
 import android.support.v4.media.session.MediaSessionCompat;
 import android.support.v4.media.session.PlaybackStateCompat;
 import android.support.v7.media.MediaRouter;

 import com.example.android.uamp.model.MusicProvider;
 import com.example.android.uamp.playback.CastPlayback;
 import com.example.android.uamp.playback.LocalPlayback;
 import com.example.android.uamp.playback.Playback;
 import com.example.android.uamp.playback.PlaybackManager;
 import com.example.android.uamp.playback.PlaybackServiceCallback;
 import com.example.android.uamp.playback.QueueManager;
 import com.example.android.uamp.playback.QueueServiceCallback;
 import com.example.android.uamp.ui.MediaBrowserFragment;
 import com.example.android.uamp.ui.NowPlayingActivity;
 import com.example.android.uamp.utils.CarHelper;
 import com.example.android.uamp.utils.LogHelper;
 import com.example.android.uamp.utils.TvHelper;
 import com.example.android.uamp.utils.WearHelper;
 import com.google.android.gms.cast.framework.CastContext;
 import com.google.android.gms.cast.framework.CastSession;
 import com.google.android.gms.cast.framework.SessionManager;
 import com.google.android.gms.cast.framework.SessionManagerListener;
 import com.google.android.gms.common.ConnectionResult;
 import com.google.android.gms.common.GoogleApiAvailability;

 import java.lang.ref.WeakReference;
 import java.util.ArrayList;
 import java.util.List;

 import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_EMPTY_ROOT;
 import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_ROOT;

 /**
  * This class provides a MediaBrowser through a service. It exposes the media library to a browsing
  * client, through the onGetRoot and onLoadChildren methods. It also creates a MediaSession and
  * exposes it through its MediaSession.Token, which allows the client to create a MediaController
  * that connects to and send control commands to the MediaSession remotely. This is useful for
  * user interfaces that need to interact with your media session, like Android Auto. You can
  * (should) also use the same service from your app's UI, which gives a seamless playback
  * experience to the user.
  * <p>
  * To implement a MediaBrowserService, you need to:
  *
  * <ul>
  *
  * <li> Extend {@link android.service.media.MediaBrowserService}, implementing the media browsing
  *      related methods {@link android.service.media.MediaBrowserService#onGetRoot} and
  *      {@link android.service.media.MediaBrowserService#onLoadChildren};
  * <li> In onCreate, start a new {@link android.media.session.MediaSession} and notify its parent
  *      with the session's token {@link android.service.media.MediaBrowserService#setSessionToken};
  *
  * <li> Set a callback on the
  *      {@link android.media.session.MediaSession#setCallback(android.media.session.MediaSession.Callback)}.
  *      The callback will receive all the user's actions, like play, pause, etc;
  *
  * <li> Handle all the actual music playing using any method your app prefers (for example,
  *      {@link android.media.MediaPlayer})
  *
  * <li> Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
  *      {@link android.media.session.MediaSession#setPlaybackState(android.media.session.PlaybackState)}
  *      {@link android.media.session.MediaSession#setMetadata(android.media.MediaMetadata)} and
  *      {@link android.media.session.MediaSession#setQueue(java.util.List)})
  *
  * <li> Declare and export the service in AndroidManifest with an intent receiver for the action
  *      android.media.browse.MediaBrowserService
  *
  * </ul>
  * <p>
  * To make your app compatible with Android Auto, you also need to:
  *
  * <ul>
  *
  * <li> Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
  *      with a &lt;automotiveApp&gt; root element. For a media app, this must include
  *      an &lt;uses name="media"/&gt; element as a child.
  *      For example, in AndroidManifest.xml:
  *          &lt;meta-data android:name="com.google.android.gms.car.application"
  *              android:resource="@xml/automotive_app_desc"/&gt;
  *      And in res/values/automotive_app_desc.xml:
  *          &lt;automotiveApp&gt;
  *              &lt;uses name="media"/&gt;
  *          &lt;/automotiveApp&gt;
  *
  * </ul>
  *
  * @see <a href="README.md">README.md</a> for more details.
  */
 public class MusicService extends MediaBrowserServiceCompat implements PlaybackServiceCallback, QueueServiceCallback {

     private static final String TAG = LogHelper.makeLogTag(MusicService.class);

     // Extra on MediaSession that contains the Cast device name currently connected to
     public static final String EXTRA_CONNECTED_CAST = "com.example.android.uamp.CAST_NAME";
     // The action of the incoming Intent indicating that it contains a command
     // to be executed (see {@link #onStartCommand})
     public static final String ACTION_CMD = "com.example.android.uamp.ACTION_CMD";
     // The key in the extras of the incoming Intent indicating the command that
     // should be executed (see {@link #onStartCommand})
     public static final String CMD_NAME = "CMD_NAME";
     // A value of a CMD_NAME key in the extras of the incoming Intent that
     // indicates that the music playback should be paused (see {@link #onStartCommand})
     public static final String CMD_PAUSE = "CMD_PAUSE";
     // A value of a CMD_NAME key that indicates that the music playback should switch
     // to local playback from cast playback.
     public static final String CMD_STOP_CASTING = "CMD_STOP_CASTING";
     /** 音乐停止播放时，Service延时停止时长30s */
     private static final int STOP_DELAY = 30000;

     /** 歌曲数据提供者 */
     private MusicProvider mMusicProvider;
     /** 浏览授权校验器 */
     private PackageValidator mPackageValidator;
     /** 持有PlaybackManager实例，构造时将自己传入完成双向关联 */
     private PlaybackManager mPlaybackManager;
     /** MediaSession */
     private MediaSessionCompat mMediaSession;
     /** 与 MediaSession 关联的额外信息 */
     private Bundle mSessionExtras;
     /** 通知栏管理 */
     private MediaNotificationManager mNotificationManager;
     /** Google Case相关 */
     private SessionManager mCastSessionManager;
     private SessionManagerListener<CastSession> mCastSessionManagerListener;
     private MediaRouter mMediaRouter;
     /** 是否连接到Android Auto汽车 */
     private boolean mIsConnectedToCar;

     /** 与Android Auto汽车连接状态变化的广播接收器 */
     private final BroadcastReceiver mCarConnectionReceiver = new BroadcastReceiver() {
         @Override
         public void onReceive(Context context, Intent intent) {
             String connectionEvent = intent.getStringExtra(CarHelper.MEDIA_CONNECTION_STATUS);
             mIsConnectedToCar = CarHelper.MEDIA_CONNECTED.equals(connectionEvent);
             LogHelper.i(TAG, "Connection event to Android Auto: ", connectionEvent,
                     " isConnectedToCar=", mIsConnectedToCar);
         }
     };

     /** 音乐停止播放时，延时停止Service的Handler */
     private final DelayedStopHandler mDelayedStopHandler = new DelayedStopHandler(this);


     /**
      * 重复的startService()不会回调onCreate()，只会回调{@link #onStartCommand(Intent, int, int)}
      *
      * @see android.app.Service#onCreate()
      */
     @Override
     public void onCreate() {
         super.onCreate();
         LogHelper.d(TAG, "onCreate");

         mMusicProvider = new MusicProvider();
         // To make the app more responsive, fetch and cache catalog information now.
         // This can help improve the response time in the method
         // {@link #onLoadChildren(String, Result<List<MediaItem>>) onLoadChildren()}.
         mMusicProvider.retrieveMediaAsync(null /* Callback */);

         mPackageValidator = new PackageValidator(this);

         QueueManager queueManager = new QueueManager(getResources(), mMusicProvider, this);

         // 构造 PlaybackManager 时将 QueueManager 作为参数传递进去，使播放控制层成功连接上数据层
         mPlaybackManager = new PlaybackManager(getResources(), mMusicProvider, this, queueManager, 
                 new LocalPlayback(this, mMusicProvider));

         // Start a new MediaSession
         mMediaSession = new MediaSessionCompat(this, "MusicService");
         setSessionToken(mMediaSession.getSessionToken());
         mMediaSession.setCallback(mPlaybackManager.getMediaSessionCallback());   // 设置PlaybackManager中定义的受控端回调实现类
         mMediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                 MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

         Context context = getApplicationContext();
         // 当音乐在后台播放，用户点击正在播放卡片调起这个Activity
         Intent intent = new Intent(context, NowPlayingActivity.class);
         PendingIntent pendingIntent = PendingIntent.getActivity(context, 99 /*request code*/,
                 intent, PendingIntent.FLAG_UPDATE_CURRENT);
         mMediaSession.setSessionActivity(pendingIntent);

         mSessionExtras = new Bundle();
         CarHelper.setSlotReservationFlags(mSessionExtras, true, true, true);
         WearHelper.setSlotReservationFlags(mSessionExtras, true, true);
         WearHelper.setUseBackgroundFromTheme(mSessionExtras, true);
         mMediaSession.setExtras(mSessionExtras);

         mPlaybackManager.updatePlaybackState(null);

         try {
             mNotificationManager = new MediaNotificationManager(this);
         } catch (RemoteException e) {
             throw new IllegalStateException("Could not create a MediaNotificationManager", e);
         }

         int playServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
         if (!TvHelper.isTvUiMode(this) && playServicesAvailable == ConnectionResult.SUCCESS) {
             mCastSessionManager = CastContext.getSharedInstance(this).getSessionManager();
             mCastSessionManagerListener = new CastSessionManagerListener();
             mCastSessionManager.addSessionManagerListener(mCastSessionManagerListener, CastSession.class);
         }

         mMediaRouter = MediaRouter.getInstance(getApplicationContext());
         registerReceiver(mCarConnectionReceiver, new IntentFilter(CarHelper.ACTION_MEDIA_STATUS));
     }

     /**
      * (non-Javadoc)
      *
      * @see android.app.Service#onStartCommand(android.content.Intent, int, int)
      */
     @Override
     public int onStartCommand(Intent startIntent, int flags, int startId) {
         if (startIntent != null) {
             String action = startIntent.getAction();
             String command = startIntent.getStringExtra(CMD_NAME);
             if (ACTION_CMD.equals(action)) {
                 if (CMD_PAUSE.equals(command)) {   // 耳机拔出时暂停播放音乐
                     mPlaybackManager.handlePauseRequest();
                 } else if (CMD_STOP_CASTING.equals(command)) {
                     CastContext.getSharedInstance(this).getSessionManager().endCurrentSession(true);
                 }
             } else {
                 // Try to handle the intent as a media button event wrapped by MediaButtonReceiver
                 MediaButtonReceiver.handleIntent(mMediaSession, startIntent);
             }
         }
         // Reset the delay handler to enqueue a message to stop the service if nothing is playing.
         mDelayedStopHandler.removeCallbacksAndMessages(null);
         mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
         return START_STICKY;
     }

     /*
      * Handle case when user swipes the app away from the recents apps list by
      * stopping the service (and any ongoing playback).
      */
     @Override
     public void onTaskRemoved(Intent rootIntent) {
         super.onTaskRemoved(rootIntent);
         stopSelf();
     }

     /**
      * (non-Javadoc)
      *
      * @see android.app.Service#onDestroy()
      */
     @Override
     public void onDestroy() {
         LogHelper.d(TAG, "onDestroy");
         unregisterReceiver(mCarConnectionReceiver);
         // Service is being killed, so make sure we release our resources
         mPlaybackManager.handleStopRequest(null);
         mNotificationManager.stopNotification();

         if (mCastSessionManager != null) {
             mCastSessionManager.removeSessionManagerListener(mCastSessionManagerListener,
                     CastSession.class);
         }

         mDelayedStopHandler.removeCallbacksAndMessages(null);
         mMediaSession.release();
     }

     /**
      * MediaBrowser向MediaBrowserService发起连接时回调，通过返回值决定是否允许该客户端连接服务
      *
      * @param clientPackageName
      * @param clientUid
      * @param rootHints
      * @return
      */
     @Override
     public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, Bundle rootHints) {
         LogHelper.d(TAG, "OnGetRoot: clientPackageName=" + clientPackageName,
                 "; clientUid=" + clientUid + " ; rootHints=", rootHints);
         // To ensure you are not allowing any arbitrary app to browse your app's contents, you
         // need to check the origin:
         if (!mPackageValidator.isCallerAllowed(this, clientPackageName, clientUid)) {
             // If the request comes from an untrusted package, return an empty browser root.
             // If you return null, then the media browser will not be able to connect and
             // no further calls will be made to other media browsing methods.
             LogHelper.i(TAG, "OnGetRoot: Browsing NOT ALLOWED for unknown caller. "
                     + "Returning empty browser root so all apps can use MediaController."
                     + clientPackageName);
             return new MediaBrowserServiceCompat.BrowserRoot(MEDIA_ID_EMPTY_ROOT, null);
         }
         //noinspection StatementWithEmptyBody
         if (CarHelper.isValidCarPackage(clientPackageName)) {
             // Optional: if your app needs to adapt the music library to show a different subset
             // when connected to the car, this is where you should handle it.
             // If you want to adapt other runtime behaviors, like tweak ads or change some behavior
             // that should be different on cars, you should instead use the boolean flag
             // set by the BroadcastReceiver mCarConnectionReceiver (mIsConnectedToCar).
         }
         //noinspection StatementWithEmptyBody
         if (WearHelper.isValidWearCompanionPackage(clientPackageName)) {
             // Optional: if your app needs to adapt the music library for when browsing from a
             // Wear device, you should return a different MEDIA ROOT here, and then,
             // on onLoadChildren, handle it accordingly.
         }
         return new BrowserRoot(MEDIA_ID_ROOT, null);
     }

     /**
      * MediaBrowser向MediaBrowserService发起数据订阅时回调，一般在这里执行异步获取数据的操作，最后将数据发送至MediaBrowser的回调接口中
      * 如在{@link MediaBrowserFragment#onConnected()}中进行subscribe，回调到此处获取数据，
      * 最后将数据发送到{@link MediaBrowserFragment#mSubscriptionCallback}的onChildrenLoaded()中
      *
      * @param parentMediaId
      * @param result
      */
     @Override
     public void onLoadChildren(@NonNull final String parentMediaId, @NonNull final Result<List<MediaItem>> result) {
         LogHelper.d(TAG, "OnLoadChildren: parentMediaId=", parentMediaId);
         if (MEDIA_ID_EMPTY_ROOT.equals(parentMediaId)) {    // 如果之前验证客户端没有权限请求数据，则返回一个空的列表
             result.sendResult(new ArrayList<>());
         } else if (mMusicProvider.isInitialized()) {        // 如果歌曲库已经准备好了，立即返回
             result.sendResult(mMusicProvider.getChildren(getResources(), parentMediaId));
         } else {    // 歌曲数据获取完毕后返回结果
             result.detach();
             mMusicProvider.retrieveMediaAsync(new MusicProvider.Callback() {
                 @Override
                 public void onMusicCatalogReady(boolean success) {
                     result.sendResult(mMusicProvider.getChildren(getResources(), parentMediaId));
                 }
             });
         }
     }

     /**
      * Callback method called from QueueManager whenever the Metadata is changed.
      */
     @Override
     public void onQueueUpdated(String title, List<MediaSessionCompat.QueueItem> newQueue) {
         mMediaSession.setQueue(newQueue);  // 触发回调 MediaControllerCompat.Callback#onQueueChanged(List)
         mMediaSession.setQueueTitle(title);// 触发回调 MediaControllerCompat.Callback#onQueueTitleChanged(CharSequence)
     }

     @Override
     public void onMetadataChanged(MediaMetadataCompat metadata) {
         mMediaSession.setMetadata(metadata);// 触发回调 MediaControllerCompat.Callback#onMetadataChanged(metadata)
     }

     @Override
     public void onCurrentQueueIndexUpdated(int queueIndex) {
         mPlaybackManager.handlePlayRequest();
     }

     @Override
     public void onMetadataRetrieveError() {
         mPlaybackManager.updatePlaybackState(getString(R.string.error_no_metadata));
     }


     /**
      * Callback method called from PlaybackManager whenever the music is about to play.
      */
     @Override
     public void onPlaybackStart() {
         mMediaSession.setActive(true);
         mDelayedStopHandler.removeCallbacksAndMessages(null);

         // The service needs to continue running even after the bound client (usually a
         // MediaController) disconnects, otherwise the music playback will stop.
         // Calling startService(Intent) will keep the service running until it is explicitly killed.
         startService(new Intent(getApplicationContext(), MusicService.class));
     }

     @Override
     public void onNotificationRequired() {
         mNotificationManager.startNotification();
     }

     @Override
     public void onPlaybackStateUpdated(PlaybackStateCompat newState) {
         mMediaSession.setPlaybackState(newState);
     }

     @Override
     public void onPlaybackStop() {
         mMediaSession.setActive(false);
         // Reset the delayed stop handler, so after STOP_DELAY it will be executed again,
         // potentially stopping the service.
         mDelayedStopHandler.removeCallbacksAndMessages(null);
         mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
         stopForeground(true);
     }

     /**
      * A simple handler that stops the service if playback is not active (playing)
      */
     private static class DelayedStopHandler extends Handler {

         private final WeakReference<MusicService> mWeakReference;

         private DelayedStopHandler(MusicService service) {
             mWeakReference = new WeakReference<>(service);
         }

         @Override
         public void handleMessage(Message msg) {
             MusicService service = mWeakReference.get();
             if (service != null && service.mPlaybackManager.getPlayback() != null) {
                 if (service.mPlaybackManager.getPlayback().isPlaying()) {
                     LogHelper.d(TAG, "Ignoring delayed stop since the media player is in use.");
                     return;
                 }
                 LogHelper.d(TAG, "Stopping service with delay handler.");
                 service.stopSelf();
             }
         }
     }

     /**
      * Session Manager Listener responsible for switching the Playback instances
      * depending on whether it is connected to a remote player.
      */
     private class CastSessionManagerListener implements SessionManagerListener<CastSession> {

         @Override
         public void onSessionStarting(CastSession session) {
         }

         @Override
         public void onSessionStarted(CastSession session, String sessionId) {
             // In case we are casting, send the device name as an extra on MediaSession metadata.
             mSessionExtras.putString(EXTRA_CONNECTED_CAST, session.getCastDevice().getFriendlyName());
             mMediaSession.setExtras(mSessionExtras);
             // Now we can switch to CastPlayback
             Playback playback = new CastPlayback(MusicService.this, mMusicProvider);
             mMediaRouter.setMediaSessionCompat(mMediaSession);
             mPlaybackManager.switchToPlayback(playback, true);
         }

         @Override
         public void onSessionStartFailed(CastSession session, int error) {
         }

         @Override
         public void onSessionEnding(CastSession session) {
             // This is our final chance to update the underlying stream position
             // In onSessionEnded(), the underlying CastPlayback#mRemoteMediaClient
             // is disconnected and hence we update our local value of stream position
             // to the latest position.
             mPlaybackManager.getPlayback().updateLastKnownStreamPosition();
         }

         @Override
         public void onSessionEnded(CastSession session, int error) {
             LogHelper.d(TAG, "onSessionEnded");
             mSessionExtras.remove(EXTRA_CONNECTED_CAST);
             mMediaSession.setExtras(mSessionExtras);
             Playback playback = new LocalPlayback(MusicService.this, mMusicProvider);
             mMediaRouter.setMediaSessionCompat(null);
             mPlaybackManager.switchToPlayback(playback, false);
         }

         @Override
         public void onSessionResuming(CastSession session, String sessionId) {
         }

         @Override
         public void onSessionResumed(CastSession session, boolean wasSuspended) {
         }

         @Override
         public void onSessionResumeFailed(CastSession session, int error) {
         }

         @Override
         public void onSessionSuspended(CastSession session, int reason) {
         }
     }
 }
