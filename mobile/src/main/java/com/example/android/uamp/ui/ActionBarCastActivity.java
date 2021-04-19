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

import android.app.ActivityOptions;
import android.app.FragmentManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.MediaRouteButton;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastState;
import com.google.android.gms.cast.framework.CastStateListener;
import com.google.android.gms.cast.framework.IntroductoryOverlay;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/**
 * 带有顶bar、侧滑导航栏并且支持Google Cast的Activity抽象基类
 * 它的子类具有三个强制性的布局元素：
 * {@link android.support.v7.widget.Toolbar} with id 'toolbar',
 * {@link android.support.v4.widget.DrawerLayout} with id 'drawerLayout' and
 * {@link android.widget.ListView} with id 'drawerList'.
 * 需要在{@link #onCreate(Bundle)} 中 {@link #setContentView(View)} 之后调用 {@link #initializeToolbar()}
 */
public abstract class ActionBarCastActivity extends AppCompatActivity implements DrawerLayout.DrawerListener,
        CastStateListener, FragmentManager.OnBackStackChangedListener {

    private static final String TAG = LogHelper.makeLogTag(ActionBarCastActivity.class);
    /** Google Cast新手上路延迟展示时间 */
    private static final int FIRST_TIME_USER_DELAY_MILLIS = 1000;
    /** Google Cast类似于苹果的AirPlay，是一套通过网络播放媒体的技术，可以将手机、Pad、浏览器的内容投射到连接有Chromecast设备的屏幕上 */
    private CastContext mCastContext;
    /** Google Cast Icon */
    private MenuItem mMediaRouteMenuItem;
    /** 抽屉布局 */
    private DrawerLayout mDrawerLayout;
    /** 顶bar */
    private Toolbar mToolbar;
    /** 实现侧滑菜单与顶Bar的联动 */
    private ActionBarDrawerToggle mDrawerToggle;
    /** 是否完成顶bar初始化 */
    private boolean mToolbarInitialized;
    /** 记录用户选择的侧滑菜单项id */
    private int mSelectedMenuItemId = -1;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelper.d(TAG, "Activity onCreate");
        int playServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (playServicesAvailable == ConnectionResult.SUCCESS) {
            mCastContext = CastContext.getSharedInstance(this);
        }
    }

    /**
     * 初始化顶bar，紧随 {@link #setContentView(View)} 之后调用
     */
    protected void initializeToolbar() {
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        if (mToolbar == null) {
            throw new IllegalStateException("Layout is required to include a Toolbar with id 'toolbar'");
        }
        mToolbar.inflateMenu(R.menu.main);
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (mDrawerLayout != null) {    // 全屏播放器没有这个控件
            // 侧滑菜单布局
            NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
            if (navigationView == null) {
                throw new IllegalStateException("Layout requires a NavigationView with id 'nav_view'");
            }
            // Create an ActionBarDrawerToggle that will handle opening/closing of the drawer:
            mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout,
                    mToolbar, R.string.open_content_drawer, R.string.close_content_drawer);
            mDrawerLayout.setDrawerListener(this);
            navigationView.setNavigationItemSelectedListener(
                    new NavigationView.OnNavigationItemSelectedListener() {
                        @Override
                        public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                            menuItem.setChecked(true);
                            // 记录选择的侧滑菜单项id，并关闭侧滑菜单，回调onDrawerClosed()
                            mSelectedMenuItemId = menuItem.getItemId();
                            mDrawerLayout.closeDrawers();
                            return true;
                        }
                    });
            if (MusicPlayerActivity.class.isAssignableFrom(getClass())) {
                navigationView.setCheckedItem(R.id.navigation_allmusic);
            } else if (PlaceholderActivity.class.isAssignableFrom(getClass())) {
                navigationView.setCheckedItem(R.id.navigation_playlists);
            }
            setSupportActionBar(mToolbar);
            updateDrawerToggle();
        } else {
            setSupportActionBar(mToolbar);
        }
        mToolbarInitialized = true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        if (mCastContext != null) {
            mMediaRouteMenuItem = CastButtonFactory
                    .setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mToolbarInitialized) {
            throw new IllegalStateException("You must run super.initializeToolbar at " +
                    "the end of your onCreate method");
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (mDrawerToggle != null) {
            mDrawerToggle.syncState();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mCastContext != null) {
            mCastContext.addCastStateListener(this);
        }
        // 任何时候当Fragment返回栈发生变化，都可能需要更新顶bar上的按钮
        // 只有在最顶层才展示汉堡一样的icon，在内部则展示返回icon
        // Whenever the fragment back stack changes, we may need to update the
        // action bar toggle: only top level screens show the hamburger-like icon, inner
        // screens - either Activities or fragments - show the "Up" icon instead.
        getFragmentManager().addOnBackStackChangedListener(this);
    }

    @Override
    public void onDrawerSlide(View drawerView, float slideOffset) {
        if (mDrawerToggle != null) {
            mDrawerToggle.onDrawerSlide(drawerView, slideOffset);
        }
    }

    @Override
    public void onDrawerOpened(View drawerView) {
        if (mDrawerToggle != null) {
            mDrawerToggle.onDrawerOpened(drawerView);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    @Override
    public void onDrawerClosed(View drawerView) {
        if (mDrawerToggle != null) {
            mDrawerToggle.onDrawerClosed(drawerView);
        }
        if (mSelectedMenuItemId >= 0) {
            Bundle extras = ActivityOptions.makeCustomAnimation(
                    ActionBarCastActivity.this, R.anim.fade_in, R.anim.fade_out).toBundle();
            Class activityClass = null;
            switch (mSelectedMenuItemId) {
                case R.id.navigation_allmusic:
                    activityClass = MusicPlayerActivity.class;
                    break;
                case R.id.navigation_playlists:
                    activityClass = PlaceholderActivity.class;
                    break;
            }
            if (activityClass != null) {
                startActivity(new Intent(ActionBarCastActivity.this, activityClass), extras);
                finish();
            }
        }
    }

    @Override
    public void onDrawerStateChanged(int newState) {
        if (mDrawerToggle != null) {
            mDrawerToggle.onDrawerStateChanged(newState);
        }
    }

    @Override
    public void onCastStateChanged(int newState) {
        if (newState != CastState.NO_DEVICES_AVAILABLE) {  // 周围存在GoogleCast设备
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mMediaRouteMenuItem.isVisible()) {
                        LogHelper.d(TAG, "Cast Icon is visible");
                        showFirstTimeUser();
                    }
                }
            }, FIRST_TIME_USER_DELAY_MILLIS);
        }
    }

    @Override
    public void onBackStackChanged() {
        updateDrawerToggle();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mDrawerToggle != null) {
            mDrawerToggle.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mCastContext != null) {
            mCastContext.removeCastStateListener(this);
        }
        getFragmentManager().removeOnBackStackChangedListener(this);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle != null && mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        // If not handled by drawerToggle, home needs to be handled by returning to previous
        if (item != null && item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // If the drawer is open, back will close it
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawers();
            return;
        }
        // Otherwise, it may return to the previous fragment stack
        FragmentManager fragmentManager = getFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
        } else {
            // Lastly, it will rely on the system behavior for back
            super.onBackPressed();
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        mToolbar.setTitle(title);
    }

    @Override
    public void setTitle(int titleId) {
        super.setTitle(titleId);
        mToolbar.setTitle(titleId);
    }

    private void updateDrawerToggle() {
        if (mDrawerToggle == null) {
            return;
        }
        boolean isRoot = getFragmentManager().getBackStackEntryCount() == 0;
        mDrawerToggle.setDrawerIndicatorEnabled(isRoot);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowHomeEnabled(!isRoot);
            getSupportActionBar().setDisplayHomeAsUpEnabled(!isRoot);
            getSupportActionBar().setHomeButtonEnabled(!isRoot);
        }
        if (isRoot) {
            mDrawerToggle.syncState();
        }
    }

    /**
     * 向用户展示Google Cast 新手上路
     */
    private void showFirstTimeUser() {
        View view = mToolbar.getMenu().findItem(R.id.media_route_menu_item).getActionView();
        if (view instanceof MediaRouteButton) {
            IntroductoryOverlay overlay = new IntroductoryOverlay.Builder(this, mMediaRouteMenuItem)
                    .setTitleText(R.string.touch_to_cast)
                    .setSingleTime()
                    .build();
            overlay.show();
        }
    }
}
