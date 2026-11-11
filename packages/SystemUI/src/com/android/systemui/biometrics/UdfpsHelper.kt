/*
 * Copyright (C) 2024-2025 The LineageOS Project
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

package com.android.systemui.biometrics

import android.animation.ValueAnimator
import android.annotation.UiThread
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.isPulsing
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.power.domain.interactor.PowerInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "UdfpsHelper"

/**
 * Facilitates implementations that use GHBM where dim layer
 * and pressed icon aren't controlled by kernel
 */
@UiThread
class UdfpsHelper(
    private val context: Context,
    private val windowManager: WindowManager,
    @RequestReason val requestReason: Int,
    private val brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    private val powerInteractor: PowerInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private var view: View = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)!!
    private val isKeyguard = requestReason == REASON_AUTH_KEYGUARD

    private var isDimLayerAdded = false

    private val display: Display? get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    // The brightness the panel is actually driven at, which includes the doze brightness
    private val currentBrightness: Float get() =
        display?.brightnessInfo?.adjustedBrightness
            ?: displayManager.getBrightness(Display.DEFAULT_DISPLAY)
    private val brightnessAlphaMap: Map<Int, Int> = context.resources
        .getStringArray(com.android.systemui.res.R.array.config_udfpsDimmingBrightnessAlphaArray)
        .associate {
            val (brightness, alpha) = it.split(",").map { value -> value.trim().toInt() }
            brightness to alpha
        }
    private val minPanelBrightness: Int = brightnessAlphaMap.keys.min()
    val maxPanelBrightness: Int = brightnessAlphaMap.keys.max()

    private var isBrightnessMirrorShowing = false

    // Pulses keep the panel on, while AOD and doze put it in a low power mode or turn it off,
    // where nothing can light up the sensor area
    private val isDisplayOn: Boolean get() = display?.state == Display.STATE_ON

    // The display stays on through the screen off animation after going to sleep, so only
    // a pulse may show the dim layer while asleep
    private val isAwakeOrPulsing: Boolean get() =
        powerInteractor.detailedWakefulness.value.isAwake() ||
            keyguardInteractor.dozeTransitionModel.value.to.isPulsing()

    private val dimLayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
        0 /* flags are set in computeLayoutParams() */,
        PixelFormat.TRANSPARENT
    ).apply {
        title = "Dim Layer for UDFPS"
        fitInsetsTypes = 0
        gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        flags = Utils.FINGERPRINT_OVERLAY_LAYOUT_PARAM_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY
        // Avoid announcing window title
        accessibilityTitle = " "
        inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
        // Run at the peak refresh rate while UDFPS is up, so a touch doesn't wait for a mode switch
        preferredMinDisplayRefreshRate = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
    }

    private val alphaAnimator = ValueAnimator().apply {
        duration = 800L
        addUpdateListener { animator ->
            dimLayoutParams.alpha = animator.animatedValue as Float
            try {
                windowManager.updateViewLayout(view, dimLayoutParams)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "View not attached to WindowManager", e)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            // A fade in already tracks the brightness and updates the layout
            if (displayId == Display.DEFAULT_DISPLAY && view.parent != null &&
                !updateVisibility()) {
                brightnessToAlpha()
                windowManager.updateViewLayout(view, dimLayoutParams)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {}
    }

    private fun interpolate(
        value: Float,
        fromMin: Int,
        fromMax: Int,
        toMin: Int,
        toMax: Int
    ): Float {
        return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin)
    }

    private fun interpolateAlpha(brightness: Int): Float {
        val lowerEntry = brightnessAlphaMap.entries
            .lastOrNull { it.key <= brightness } ?: return 0f
        val upperEntry = brightnessAlphaMap.entries
            .firstOrNull { it.key >= brightness } ?: return 0f
        val (lowerBrightness, lowerAlpha) = lowerEntry
        val (upperBrightness, upperAlpha) = upperEntry

        return interpolate(
            brightness.toFloat(),
            lowerBrightness,
            upperBrightness,
            lowerAlpha,
            upperAlpha
        ).div(255.0f)
    }

    // While it's possible to operate with floats, the dimming array was made by referencing
    // brightness_alpha_lut array from the kernel. This provides a comparable array.
    // The doze brightness can go below the lowest entry, which then gets the most dimming.
    private fun brightnessToAlpha() {
        val adjustedBrightness = (currentBrightness * maxPanelBrightness).toInt()
            .coerceIn(minPanelBrightness, maxPanelBrightness)

        val targetAlpha = brightnessAlphaMap[adjustedBrightness]?.div(255.0f)
            ?: interpolateAlpha(adjustedBrightness)

        Log.i(TAG, "Adjusted Brightness: $adjustedBrightness, Alpha: $targetAlpha")

        alphaAnimator.cancel()
        alphaAnimator.setFloatValues(dimLayoutParams.alpha, targetAlpha)
        // Keep the black view opaque: view and window alpha multiply during composition.
        dimLayoutParams.alpha = targetAlpha
    }

    fun addDimLayer() {
        if (isDimLayerAdded) {
            return
        }
        brightnessToAlpha()
        windowManager.addView(view, dimLayoutParams)
        isDimLayerAdded = true
        displayManager.registerDisplayListener(
            displayListener,
            null,
            DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or
                DisplayManager.EVENT_TYPE_DISPLAY_STATE or
                DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS,
        )
        updateVisibility()
    }

    fun removeDimLayer() {
        if (!isDimLayerAdded) {
            return
        }
        isDimLayerAdded = false
        alphaAnimator.cancel()
        displayManager.unregisterDisplayListener(displayListener)
        if (view.parent != null) {
            windowManager.removeView(view)
        }
    }

    init {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                listenForBrightnessMirror(this)
                if (isKeyguard) {
                    listenForWakefulness(this)
                }
            }
        }
    }

    // Returns whether the dim layer started fading in
    private fun updateVisibility(): Boolean {
        val visible = !isBrightnessMirrorShowing &&
            (!isKeyguard || (isDisplayOn && isAwakeOrPulsing))
        if (view.isVisible == visible) return false

        view.isVisible = visible
        if (!visible || !isKeyguard) return false

        brightnessToAlpha()
        alphaAnimator.start()
        return true
    }

    private suspend fun listenForBrightnessMirror(scope: CoroutineScope): Job {
        return scope.launch {
            brightnessMirrorShowingInteractor.isShowing.collect {
                isBrightnessMirrorShowing = it
                updateVisibility()
            }
        }
    }

    private suspend fun listenForWakefulness(scope: CoroutineScope): Job {
        return scope.launch {
            combine(powerInteractor.isAwake, keyguardInteractor.dozeTransitionModel) { _, _ -> }
                .collect { updateVisibility() }
        }
    }
}
