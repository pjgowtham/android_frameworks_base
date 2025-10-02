/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.settings.brightness.domain.interactor

import android.hardware.display.DisplayManagerInternal
import android.os.PowerManager
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Provides proactive updates for target display brightness.
 */
@SysUISingleton
class DisplayBrightnessInteractor @Inject constructor(
    private val displayManagerInternal: DisplayManagerInternal,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val _brightness = MutableStateFlow(PowerManager.BRIGHTNESS_INVALID_FLOAT)
    /** Emits the target brightness calculated by auto-brightness before it's applied. */
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val targetBrightnessListener =
        object : DisplayManagerInternal.TargetBrightnessListener {
            override fun onTargetBrightnessChanged(displayId: Int, newBrightness: Float) {
                // We only care about the default display for now.
                if (displayId == Display.DEFAULT_DISPLAY) {
                    _brightness.value = newBrightness
                }
            }
        }

    /**
     * Starts listening for proactive brightness changes.
     *
     * This should be called once during SystemUI startup.
     */
    suspend fun start() {
        withContext(backgroundDispatcher) {
            displayManagerInternal.registerTargetBrightnessListener(targetBrightnessListener)
        }
    }
}

