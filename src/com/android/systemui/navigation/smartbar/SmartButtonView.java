/**
 * Copyright (C) 2016 The DirtyUnicorns Project
 * 
 * @author: Randall Rushing <randall.rushing@gmail.com>
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

package com.android.systemui.navigation.smartbar;

import com.android.systemui.navigation.smartbar.SmartBarView;
import com.android.systemui.navigation.smartbar.SmartButtonRipple;
import com.android.systemui.navigation.OpaLayout;
import com.android.internal.utils.du.ActionHandler;
import com.android.internal.utils.du.Config.ActionConfig;
import com.android.internal.utils.du.Config.ButtonConfig;
import com.android.systemui.navigation.Res;

import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.util.AttributeSet;
//import android.content.res.ThemeConfig;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

public class SmartButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";
    private static final boolean DEBUG = false;

    // AOSP values feel rather slow, shave off some slack
    // changing double tap timeout also affects single tap
    // so we can't play so much with it
    private static final int DT_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
    private static int sDoubleTapTimeout = DT_TIMEOUT - 100;

    private static int sLongPressTimeout;

    public static final int ANIM_STYLE_RIPPLE = 0;
    public static final int ANIM_STYLE_FLIP = 2;
    public static final int ANIM_STYLE_PIXEL = 3;
    public static final int ANIM_STYLE_PIXEL_HOME = 4;
    public static final int ANIM_STYLE_PIXEL_HOME_RIPPLE = 5;

    private boolean isDoubleTapPending;
    private boolean wasConsumed;
    private boolean mInEditMode;
    private int mAnimStyle = 0;
    private ObjectAnimator mFlipAnim = null;
    private ButtonConfig mConfig;
    private SmartBarView mHost;

    static AudioManager mAudioManager;
    static AudioManager getAudioManager(Context context) {
        if (mAudioManager == null)
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return mAudioManager;
    }

    public SmartButtonView(Context context) {
        this(context, null);
    }

    public SmartButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SmartButtonView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SmartButtonView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setClickable(true);
        setLongClickable(false);
        mAudioManager = getAudioManager(context);
    }

    public void setHost(SmartBarView host) {
        mHost = host;
    }

    public void setAnimationStyle(int style) {
        mAnimStyle = style;
        switch (style) {
            case ANIM_STYLE_RIPPLE:
                setPixelEnabled(false, false, false);
                setRippleEnabled(true);
                mFlipAnim = null;
                break;
            case ANIM_STYLE_FLIP:
                setPixelEnabled(false, false, false);
                setRippleEnabled(false);
                break;
            case ANIM_STYLE_PIXEL:
                setPixelEnabled(true, false, false);
                setRippleEnabled(false);
                mFlipAnim = null;
                break;
            case ANIM_STYLE_PIXEL_HOME:
                setPixelEnabled(true, true, false);
                setRippleEnabled(false);
                mFlipAnim = null;
                break;
            case ANIM_STYLE_PIXEL_HOME_RIPPLE:
                setPixelEnabled(true, true, true);
                mFlipAnim = null;
                break;
        }
    }

    public void setPixelEnabled(boolean enabled, boolean homeOnly, boolean rippleForOthers) {
        if (getParent() != null && getParent() instanceof OpaLayout) {
            OpaLayout opa = (OpaLayout)getParent();
            opa.setOpaEnabled(enabled);
            if (enabled) {
                boolean isHomeButton = TextUtils.equals(mConfig.getTag(), Res.Softkey.BUTTON_HOME);
                if (!rippleForOthers) {
                    opa.setOpaVisibilityHome(homeOnly, isHomeButton);
                } else {
                    if (isHomeButton) {
                        opa.setOpaVisibilityHome(homeOnly, isHomeButton);
                    } else {
                        opa.setOpaEnabled(!enabled);
                        setRippleEnabled(enabled);
                    }
                }
            }
        }
    }

    private void setRippleEnabled(boolean enabled) {
        if (getBackground() != null && getBackground() instanceof SmartButtonRipple) {
            SmartButtonRipple background = (SmartButtonRipple) getBackground();
            background.setEnabled(enabled);
        }
    }

    private void fireActionIfSecure(String action) {
        final boolean keyguardShowing = mHost.isKeyguardShowing();
        if (!keyguardShowing
                || (keyguardShowing && ActionHandler.SYSTEMUI_TASK_BACK.equals(action))) {
             ActionHandler.performTask(mContext, action);
        }
    }

    public void loadRipple() {
        setBackground(new SmartButtonRipple(mContext, this));
    }

    public void setEditMode(boolean editMode) {
        mInEditMode = editMode;
        if (getParent() != null && getParent() instanceof OpaLayout) {
            OpaLayout opa = (OpaLayout)getParent();
            opa.setEditMode(editMode);
        }
    }

    public void setButtonConfig(ButtonConfig config) {
        mConfig = config;
        setTag(config.getTag());
        setLongClickable(hasLongAction());
    }

    private boolean hasSingleAction() {
        return mConfig != null && !mConfig.getActionConfig(ActionConfig.PRIMARY).hasNoAction();
    }

    private boolean hasLongAction() {
        return mConfig != null && !mConfig.getActionConfig(ActionConfig.SECOND).hasNoAction();
    }

    private boolean hasDoubleAction() {
        return mConfig != null && !mConfig.getActionConfig(ActionConfig.THIRD).hasNoAction();
    }

    private boolean hasRecentAction() {
        return hasRecentsSingle() || hasRecentsLong() || hasRecentsDouble();
    }

    private boolean hasRecentsSingle() {
        return mConfig != null && mConfig.getActionConfig(ActionConfig.PRIMARY).isActionRecents();
    }

    private boolean hasRecentsLong() {
        return mConfig != null && mConfig.getActionConfig(ActionConfig.SECOND).isActionRecents();
    }

    private boolean hasRecentsDouble() {
        return mConfig != null && mConfig.getActionConfig(ActionConfig.THIRD).isActionRecents();
    }

    public ButtonConfig getButtonConfig() {
        return mConfig;
    }

    private boolean mIsEmptyFakeButton() {
        return !hasSingleAction()
                && !hasLongAction()
                && !hasDoubleAction();
    }

    // special case: double tap for screen off we never capture up motion event
    // add/remove listeners if screen on/off
    public void onScreenStateChanged(boolean screenOn) {
        wasConsumed = false;
        setPressed(false);
    }

    private void checkAndDoFlipAnim() {
        if (mAnimStyle == ANIM_STYLE_FLIP) {
            mFlipAnim = ObjectAnimator.ofFloat(this, "rotationY", 0f, 360f);
            mFlipAnim.setDuration(1500);
            mFlipAnim.setInterpolator(new OvershootInterpolator());
            mFlipAnim.start();
        }
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (mIsEmptyFakeButton())
                return false;

        OpaLayout opa = null;
        if (getParent() != null && getParent() instanceof OpaLayout) {
            opa = (OpaLayout)getParent();
        }
        if (mInEditMode) {
            return false;
        }
        final int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                if (opa != null) {
                    opa.startDownAction();
                }
                checkAndDoFlipAnim();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                playSoundEffect(SoundEffectConstants.CLICK);
                if (isDoubleTapPending) {
                    isDoubleTapPending = false;
                    wasConsumed = true;
                    removeCallbacks(mDoubleTapTimeout);
                    doDoubleTap();
                } else {
                    wasConsumed = false;
                    if (hasRecentAction()) {
                        ActionHandler.preloadRecentApps();
                    }
                    if (hasLongAction()) {
                        removeCallbacks(mCheckLongPress);
                        postDelayed(mCheckLongPress, sLongPressTimeout);
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (hasLongAction()) {
                    removeCallbacks(mCheckLongPress);
                }
                removeCallbacks(mDoubleTapTimeout);
                wasConsumed = true;
                isDoubleTapPending = false;
                setPressed(false);
                if (opa != null) {
                    opa.startCancelAction();
                }
                break;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                checkAndDoFlipAnim();
                if (opa != null) {
                    opa.startCancelAction();
                }
                if (hasLongAction()) {
                    removeCallbacks(mCheckLongPress);
                }
                if (hasDoubleAction()) {
                    if (wasConsumed) {
                        wasConsumed = false;
                        return true;
                    }
                    isDoubleTapPending = true;
                    postDelayed(mDoubleTapTimeout, sDoubleTapTimeout);
                } else {
                    if (!wasConsumed && hasSingleAction()) {
                        doSinglePress();
                    }
                }
                break;
        }
        return true;
    }

    private void doSinglePress() {
        isDoubleTapPending = false;
        if (mConfig != null) {
            String action = mConfig.getActionConfig(ActionConfig.PRIMARY).getAction();
            fireActionIfSecure(action);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        }
    }

    private void doLongPress() {
        isDoubleTapPending = false;
        wasConsumed = true;
        if (mConfig != null) {
            String action = mConfig.getActionConfig(ActionConfig.SECOND).getAction();
            fireActionIfSecure(action);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            playSoundEffect(SoundEffectConstants.CLICK);
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
        }
    }

    private void doDoubleTap() {
        isDoubleTapPending = false;
        wasConsumed = true;
        if (mConfig != null) {
            String action = mConfig.getActionConfig(ActionConfig.THIRD).getAction();
            fireActionIfSecure(action);
        }
    }

    private Runnable mDoubleTapTimeout = new Runnable() {
        @Override
        public void run() {
            wasConsumed = false;
            isDoubleTapPending = false;
            doSinglePress();
        }
    };

    private Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                wasConsumed = true;
                isDoubleTapPending = false;
                removeCallbacks(mDoubleTapTimeout);
                doLongPress();
            }
        }
    };

    public void playSoundEffect(int soundConstant) {
        mAudioManager.playSoundEffect(soundConstant, ActivityManager.getCurrentUser());
    };

    protected static void setButtonLongpressDelay(int delay) {
        sLongPressTimeout = delay;
    }
}
