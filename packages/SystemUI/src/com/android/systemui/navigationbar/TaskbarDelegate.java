/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.systemui.navigationbar;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.containsType;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import android.app.StatusBarManager;
import android.app.StatusBarManager.WindowVisibleState;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.InsetsVisibilities;
import android.view.View;
import android.view.WindowInsetsController.Behavior;

import androidx.annotation.NonNull;

import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TaskbarDelegate implements CommandQueue.Callbacks,
        OverviewProxyService.OverviewProxyListener, NavigationModeController.ModeChangedListener,
        ComponentCallbacks, Dumpable {
    private static final String TAG = TaskbarDelegate.class.getSimpleName();

    private final EdgeBackGestureHandler mEdgeBackGestureHandler;
    private boolean mInitialized;
    private CommandQueue mCommandQueue;
    private OverviewProxyService mOverviewProxyService;
    private NavBarHelper mNavBarHelper;
    private NavigationModeController mNavigationModeController;
    private SysUiState mSysUiState;
    private AutoHideController mAutoHideController;
    private LightBarController mLightBarController;
    private LightBarTransitionsController mLightBarTransitionsController;
    private int mDisplayId;
    private int mNavigationIconHints;
    private final NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater =
            new NavBarHelper.NavbarTaskbarStateUpdater() {
                @Override
                public void updateAccessibilityServicesState() {
                    updateSysuiFlags();
                }

                @Override
                public void updateAssistantAvailable(boolean available) {
                    updateAssistantAvailability(available);
                }
            };
    private int mDisabledFlags;
    private @WindowVisibleState int mTaskBarWindowState = WINDOW_STATE_SHOWING;
    private @Behavior int mBehavior;
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private Context mWindowContext;
    /**
     * Tracks the system calls for when taskbar should transiently show or hide so we can return
     * this value in {@link AutoHideUiElement#isVisible()} below.
     *
     * This also gets set by {@link #onTaskbarAutohideSuspend(boolean)} to force show the transient
     * taskbar if launcher has requested to suspend auto-hide behavior.
     */
    private boolean mTaskbarTransientShowing;
    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
        }

        @Override
        public boolean isVisible() {
            return mTaskbarTransientShowing;
        }

        @Override
        public void hide() {
        }
    };

    @Inject
    public TaskbarDelegate(Context context) {
        mEdgeBackGestureHandler = Dependency.get(EdgeBackGestureHandler.Factory.class)
                .create(context);
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
    }

    public void setDependencies(CommandQueue commandQueue,
            OverviewProxyService overviewProxyService,
            NavBarHelper navBarHelper,
            NavigationModeController navigationModeController,
            SysUiState sysUiState, DumpManager dumpManager,
            AutoHideController autoHideController,
            LightBarController lightBarController) {
        // TODO: adding this in the ctor results in a dagger dependency cycle :(
        mCommandQueue = commandQueue;
        mOverviewProxyService = overviewProxyService;
        mNavBarHelper = navBarHelper;
        mNavigationModeController = navigationModeController;
        mSysUiState = sysUiState;
        dumpManager.registerDumpable(this);
        mAutoHideController = autoHideController;
        mLightBarController = lightBarController;
        mLightBarTransitionsController = createLightBarTransitionsController();
    }

    // Separated into a method to keep setDependencies() clean/readable.
    private LightBarTransitionsController createLightBarTransitionsController() {
        return new LightBarTransitionsController(mContext,
                new LightBarTransitionsController.DarkIntensityApplier() {
                    @Override
                    public void applyDarkIntensity(float darkIntensity) {
                        mOverviewProxyService.onNavButtonsDarkIntensityChanged(darkIntensity);
                    }

                    @Override
                    public int getTintAnimationDuration() {
                        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
                    }
                }, mCommandQueue) {
            @Override
            public boolean supportsIconTintForNavMode(int navigationMode) {
                // Always tint taskbar nav buttons (region sampling handles gesture bar separately).
                return true;
            }
        };
    }

    public void init(int displayId) {
        if (mInitialized) {
            return;
        }
        mDisplayId = displayId;
        mCommandQueue.addCallback(this);
        mOverviewProxyService.addCallback(this);
        mEdgeBackGestureHandler.onNavigationModeChanged(
                mNavigationModeController.addListener(this));
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.init(mContext);
        mEdgeBackGestureHandler.onNavBarAttached();
        // Initialize component callback
        Display display = mDisplayManager.getDisplay(displayId);
        mWindowContext = mContext.createWindowContext(display, TYPE_APPLICATION, null);
        mWindowContext.registerComponentCallbacks(this);
        // Set initial state for any listeners
        updateSysuiFlags();
        mAutoHideController.setNavigationBar(mAutoHideUiElement);
        mLightBarController.setNavigationBar(mLightBarTransitionsController);
        mInitialized = true;
    }

    public void destroy() {
        if (!mInitialized) {
            return;
        }
        mCommandQueue.removeCallback(this);
        mOverviewProxyService.removeCallback(this);
        mNavigationModeController.removeListener(this);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.destroy();
        mEdgeBackGestureHandler.onNavBarDetached();
        if (mWindowContext != null) {
            mWindowContext.unregisterComponentCallbacks(this);
            mWindowContext = null;
        }
        mAutoHideController.setNavigationBar(null);
        mLightBarTransitionsController.destroy(mContext);
        mLightBarController.setNavigationBar(null);
        mInitialized = false;
    }

    private void updateSysuiFlags() {
        int a11yFlags = mNavBarHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiState.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SHOWN) != 0)
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_BACK_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isWindowVisible())
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .setFlag(SYSUI_STATE_SCREEN_PINNING,
                        ActivityManagerWrapper.getInstance().isScreenPinningActive())
                .commitUpdate(mDisplayId);
    }

    private void updateAssistantAvailability(boolean assistantAvailable) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().onAssistantAvailable(assistantAvailable);
        } catch (RemoteException e) {
            Log.e(TAG, "onAssistantAvailable() failed, available: " + assistantAvailable, e);
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints != mNavigationIconHints) {
            mNavigationIconHints = hints;
            updateSysuiFlags();
        }
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mTaskBarWindowState != state) {
            mTaskBarWindowState = state;
            updateSysuiFlags();
        }
    }

    @Override
    public void onRotationProposal(int rotation, boolean isValid) {
        mOverviewProxyService.onRotationProposal(rotation, isValid);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        mDisabledFlags = state1;
        updateSysuiFlags();
        mOverviewProxyService.disable(displayId, state1, state2, animate);
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme, int behavior,
            InsetsVisibilities requestedVisibilities, String packageName) {
        mOverviewProxyService.onSystemBarAttributesChanged(displayId, behavior);
        if (mLightBarController != null && displayId == mDisplayId) {
            mLightBarController.onNavigationBarAppearanceChanged(appearance, false/*nbModeChanged*/,
                    BarTransitions.MODE_TRANSPARENT /*navigationBarMode*/, navbarColorManagedByIme);
        }
        if (mBehavior != behavior) {
            mBehavior = behavior;
            updateSysuiFlags();
        }
    }

    @Override
    public void showTransient(int displayId, int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        mTaskbarTransientShowing = true;
    }

    @Override
    public void abortTransient(int displayId, int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        mTaskbarTransientShowing = false;
    }

    @Override
    public void onTaskbarAutohideSuspend(boolean suspend) {
        mTaskbarTransientShowing = suspend;
        if (suspend) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mEdgeBackGestureHandler.onNavigationModeChanged(mode);
    }

    private boolean isWindowVisible() {
        return mTaskBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        mEdgeBackGestureHandler.onConfigurationChanged(configuration);
    }

    @Override
    public void onLowMemory() {}

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("TaskbarDelegate (displayId=" + mDisplayId + "):");
        pw.println("  mNavigationIconHints=" + mNavigationIconHints);
        pw.println("  mDisabledFlags=" + mDisabledFlags);
        pw.println("  mTaskBarWindowState=" + mTaskBarWindowState);
        pw.println("  mBehavior=" + mBehavior);
        pw.println("  mTaskbarTransientShowing=" + mTaskbarTransientShowing);
        mEdgeBackGestureHandler.dump(pw);
    }
}
