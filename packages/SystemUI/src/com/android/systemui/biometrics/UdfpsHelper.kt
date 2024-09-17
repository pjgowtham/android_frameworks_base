package com.android.systemui.biometrics

import android.annotation.UiThread
import android.content.Context
import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricSourceType
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import com.android.systemui.keyguard.shared.model.TransitionState
import android.app.WallpaperManager
import android.app.WallpaperColors

import android.hardware.display.DisplayManager
import android.view.Display

import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricRequestConstants.RequestReason

private const val TAG = "UdfpsHelper"

/**
 * Facilitates implementations that use GHBM where high brightness mode engages
 * when the fingerprint icon is shown instead of during touchdown.
 */
@UiThread
class UdfpsHelper constructor(
        private val context: Context,
        //private val view: View,
        private val windowManager: WindowManager,
        private val shadeInteractor: ShadeInteractor,
        private val screenLifecycle: ScreenLifecycle,
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        private val alternateBouncerInteractor: AlternateBouncerInteractor,
        private val transitionInteractor: KeyguardTransitionInteractor,
        private val wakefulnessLifecycle: WakefulnessLifecycle,
) {
    private var lastArgbAmount: Int = Color.BLACK
    private var view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
    private var lastDimAmount: Float = 0.0f
    private var isDimLayerAdded = false
    private var isDisplayOn: Boolean = false
    private var isShadeExpanded: Boolean = false
    private fun interpolate(value: Int, fromMin: Int, fromMax: Int, toMin: Int, toMax: Int): Int {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun calculateDimAmount() {
        val brightness = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 0)
        if (brightness < 0) return
        
        val invertedBrightnessAsFloat = interpolate(brightness, 1, 255, 255, 0) / 255.0f   
        val max = context.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat)
        val min = context.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMinimumFloat)
        val factorMin = context.getResources().getFloat(com.android.systemui.res.R.dimen
                .config_udfpsDimmingFactorMinFloat)
        val factorMax = context.getResources().getFloat(com.android.systemui.res.R.dimen
                .config_udfpsDimmingFactorMaxFloat)
 
        lastDimAmount = invertedBrightnessAsFloat * (1.0f - min - 0.20f) + (1.0f - max - factorMax)
        Log.e(TAG, "Inverted brightness: $lastDimAmount")
    }

    private fun calculateArgb() {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val lockscreenColors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_LOCK)
        if (lockscreenColors != null) {
            val primaryColorArgb = lockscreenColors.primaryColor?.toArgb() ?: Color.BLACK
            val alpha = 255 - lastDimAmount.toInt()
            val red = Color.red(primaryColorArgb)
            val green = Color.green(primaryColorArgb)
            val blue = Color.blue(primaryColorArgb)
            Log.e(TAG, "Red: $red, Green: $green, Blue: $blue, Alpha: $alpha")
            lastArgbAmount = Color.argb(alpha, red, green, blue)
        } else {
            lastArgbAmount = Color.BLACK
        }
    }

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "Dim Layer for - Udfps"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = (Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        // Avoid setting alpha = lastDimAmount since lastDimAmount may not be initialized
        //alpha = lastDimAmount
        // Avoid announcing window title.
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
        }
        override fun onDisplayRemoved(displayId: Int) {
        }
        override fun onDisplayChanged(displayId: Int) {
            val display = displayManager.getDisplay(displayId)
            if (display != null && display.state == Display.STATE_ON) {
                Log.e(TAG, "Display turned on")
                isDisplayOn = true
                view.visibility = View.VISIBLE
            } else if (display != null && display.state == Display.STATE_OFF) {
                Log.e(TAG, "Display turned off")
                isDisplayOn = false
                view.visibility = View.GONE
            }
        }
    }

    init {
        Log.e(TAG, "Initializing")
        // Calculations are performed just before the screen turns off
        calculateDimAmount()
        dimLayoutParams.alpha = lastDimAmount
        windowManager.addView(view, dimLayoutParams)
        displayManager.registerDisplayListener(displayListener, null)
        //wakefulnessLifecycle.addObserver(wakefulnessObserver)
        view.repeatWhenAttached {
            // repeatOnLifecycle CREATED (as opposed to STARTED) because the Bouncer expansion
            // can make the view not visible; and we still want to listen for events
            // that may make the view visible again.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenToCurrentKeyguardState(this)
                listenForAnyStateToGone(this)
                listenForLockscreenToDozingTransitions(this)
                listenForShadeExpansion(this)
            }
        }
    }

    private suspend fun listenForAnyStateToGone(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.anyStateToGoneTransition.collect { transitionStep ->
                if (transitionStep.transitionState == TransitionState.FINISHED) {
                    Log.e(TAG, "Gone transition finished")
                    //wakefulnessLifecycle.removeObserver(wakefulnessObserver)
                    displayManager.unregisterDisplayListener(displayListener)
                    windowManager.removeView(view)
                }
            }
        }
    }

    private suspend fun listenToCurrentKeyguardState(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor.currentKeyguardState.collect { keyguardState ->
                Log.e(TAG, "Current Keyguard State: $keyguardState")

                when (keyguardState) {
                    KeyguardState.LOCKSCREEN -> {
                        view.visibility = View.VISIBLE
                    }
                    KeyguardState.AOD -> {
                        view.visibility = View.GONE
                    }
                    KeyguardState.OFF -> {
                        view.visibility = View.GONE
                    }
                    KeyguardState.PRIMARY_BOUNCER -> {
                        view.visibility = View.GONE
                    }
                    KeyguardState.DOZING -> {
                        if (isDisplayOn) {
                            view.visibility = View.VISIBLE
                        } else {
                            view.visibility = View.GONE
                        }
                    }
                    KeyguardState.DREAMING -> {
                        Log.e(TAG, "Keyguard is in DREAMING state")
                    }
                    KeyguardState.DREAMING_LOCKSCREEN_HOSTED -> {
                        Log.e(TAG, "Keyguard is in DREAMING_LOCKSCREEN_HOSTED state")
                    }
                    KeyguardState.ALTERNATE_BOUNCER -> {
                        view.visibility = View.VISIBLE
                    }
                    KeyguardState.GLANCEABLE_HUB -> {
                        Log.e(TAG, "Keyguard is in GLANCEABLE_HUB state")
                    }
                    KeyguardState.GONE -> {
                        Log.e(TAG, "Keyguard is in GONE state")
                    }
                    KeyguardState.OCCLUDED -> {
                        Log.e(TAG, "Keyguard is in OCCLUDED state")
                    }
                    else -> {
                        Log.e(TAG, "Keyguard is in an unknown state")
                    }
                }
            }
        }
    }

    private suspend fun listenForLockscreenToDozingTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(KeyguardState.LOCKSCREEN, KeyguardState.DOZING)
                .collect { transitionStep ->
                    Log.e(TAG, "Lockscreen to Dozing")
                    view.visibility = View.GONE
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            calculateDimAmount()
            windowManager.updateViewLayout(view, dimLayoutParams)
        }
    }

    private suspend fun listenForShadeExpansion(scope: CoroutineScope): Job {
        return scope.launch {
            shadeInteractor.anyExpansion.collect { expansion ->
                if (expansion == 1f && !isShadeExpanded) {
                    isShadeExpanded = true
                    Log.e(TAG, "Notification shade fully expanded")
                    context.contentResolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                        false,
                        brightnessObserver
                    )
                    view.visibility = View.GONE
                } else if (expansion == 0f && isShadeExpanded) {
                    Log.e(TAG, "Notification shade fully closed")
                    context.contentResolver.unregisterContentObserver(brightnessObserver)
                    isShadeExpanded = false
                    view.visibility = View.VISIBLE
                }
            }
        }
    }
}
