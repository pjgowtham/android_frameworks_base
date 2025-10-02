package com.android.systemui.dagger

import android.hardware.display.DisplayManagerInternal
import com.android.server.LocalServices
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object DisplayModule {
    @Provides
    @Singleton
    fun provideDisplayManagerInternal(): DisplayManagerInternal {
        return LocalServices.getService(DisplayManagerInternal::class.java)
    }
}
