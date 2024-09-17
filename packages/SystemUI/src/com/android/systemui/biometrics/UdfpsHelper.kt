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

private const val TAG = "UdfpsHelper"


// Issues
// Icon layer stays active when dozing or off whereas dim layer cannot
// Observing KEY_BRIGHTNESS instead of SCREEN_BRIGHTNESS
/**
 * Facilitates implementations where non-HBM states are non-relevant when
 * the device expects fingerprint to be utilized.
 */
@UiThread
class UdfpsHelper constructor(
        private val context: Context,
        private val windowManager: WindowManager,
        private val shadeInteractor: ShadeInteractor,
        private val screenLifecycle: ScreenLifecycle,
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        private val alternateBouncerInteractor: AlternateBouncerInteractor,
        private val transitionInteractor: KeyguardTransitionInteractor,
        private val wakefulnessLifecycle: WakefulnessLifecycle,
) {
    // dimlayout alpha and background color calculations are only executed
    //
    private var lastArgbAmount: Int = Color.BLACK
    private var lastDimAmount: Float = 0.0f

    private var isDimLayerVisible = false
    private var isShadeExpanded: Boolean = false
    private var view: View = View(context).apply {
        setBackgroundColor(lastArgbAmount)
    }

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedWakingUp() {
            Log.e(TAG, "Adding dim layer")
            addDimLayer()
        }
    }

    private val screenObserver = object : ScreenLifecycle.Observer {
        override fun onScreenTurningOff() {
            calculateDimAmount()
        }
    }

    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            calculateDimAmount()
            windowManager.updateViewLayout(view, dimLayoutParams)
        }
    }

    init {
        Log.e(TAG, "Initializing")
        // Calculations are performed just before the screen turns off
        calculateDimAmount()
        //calculateArgb()
        //screenLifecycle.addObserver(screenObserver)
        wakefulnessLifecycle.addObserver(wakefulnessObserver)
        view.repeatWhenAttached {
            // repeatOnLifecycle CREATED (as opposed to STARTED) because the Bouncer expansion
            // can make the view not visible; and we still want to listen for events
            // that may make the view visible again.
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForDozingToGoneTransitions(this)
                listenForLockscreenToGoneTransitions(this)
                listenForLockscreenToDozingTransitions(this)
                listenForShadeExpansion(this)
            }
        }
    }

    suspend fun listenForLockscreenToGoneTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(KeyguardState.LOCKSCREEN, KeyguardState.GONE)
                .collect { transitionStep ->
                    Log.e(TAG, "Lockscreen to gone")
                    wakefulnessLifecycle.removeObserver(wakefulnessObserver)
                    removeDimLayer()
            }
        }
    }

    suspend fun listenForDozingToGoneTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(KeyguardState.DOZING, KeyguardState.GONE)
                .collect { transitionStep ->
                    Log.e(TAG, "Dozing to gone")
                    wakefulnessLifecycle.removeObserver(wakefulnessObserver)
                    removeDimLayer()
            }
        }
    }

    suspend fun listenForLockscreenToDozingTransitions(scope: CoroutineScope): Job {
        return scope.launch {
            transitionInteractor
                .transition(KeyguardState.LOCKSCREEN, KeyguardState.DOZING)
                .collect { transitionStep ->
                    Log.e(TAG, "Lockscreen to Dozing")
                    removeDimLayer()
            }
        }
    }

    // At this point, it is expected for the user to operate the brightness slider
    // Auto Brightness adjustments are suspended by the onscreenfingerprint driver when
    // the icon layer is active
    suspend fun listenForShadeExpansion(scope: CoroutineScope): Job {
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
                } else if (expansion == 0f && isShadeExpanded) {
                    Log.e(TAG, "Notification shade fully closed")
                    context.contentResolver.unregisterContentObserver(brightnessObserver)
                    isShadeExpanded = false
                }
            }
        }
    }

    fun interpolate(value: Int, fromMin: Int, fromMax: Int, toMin: Int, toMax: Int): Int {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    // Brightness value obtained from the brightness key is better for this purpose since
    // onchange would happen as the brightness operates 
    private fun calculateDimAmount() {
        val brightness = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 0)

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

    // Not supposed to run on uithread
    // we can't make up formulae for this
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
            Log.e(TAG, "No lockscreen wallpaper found.")
        }
    }

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "UdfpsControllerDimOverlay"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = (Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        alpha = lastDimAmount
        // Avoid announcing window title.
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
    }

    private fun addDimLayer() {
        try {
            //dimLayoutParams.alpha = lastDimAmount
            windowManager.addView(view, dimLayoutParams)
            isDimLayerVisible = true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to add dim window")
        }
    }

    private fun removeDimLayer() {
        try {
            windowManager.removeView(view)
            isDimLayerVisible = false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to remove dim window")
        }
    }

}
