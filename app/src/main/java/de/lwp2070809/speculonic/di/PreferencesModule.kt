package de.lwp2070809.speculonic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.prefs.BluetoothCarPrefs
import de.lwp2070809.speculonic.data.prefs.CacheSyncPrefs
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefs
import de.lwp2070809.speculonic.data.prefs.GeneralSystemPrefs
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefs
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager {
        return PreferencesManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideConnectionPrefs(preferencesManager: PreferencesManager): ConnectionPrefs {
        return preferencesManager
    }

    @Provides
    @Singleton
    fun providePlaybackPrefs(preferencesManager: PreferencesManager): PlaybackPrefs {
        return preferencesManager
    }

    @Provides
    @Singleton
    fun provideBluetoothCarPrefs(preferencesManager: PreferencesManager): BluetoothCarPrefs {
        return preferencesManager
    }

    @Provides
    @Singleton
    fun provideCacheSyncPrefs(preferencesManager: PreferencesManager): CacheSyncPrefs {
        return preferencesManager
    }

    @Provides
    @Singleton
    fun provideGeneralSystemPrefs(preferencesManager: PreferencesManager): GeneralSystemPrefs {
        return preferencesManager
    }

    @Provides
    @Singleton
    fun providePlaybackController(@ApplicationContext context: Context): de.lwp2070809.speculonic.playback.PlaybackController {
        return de.lwp2070809.speculonic.playback.PlaybackController.getInstance(context)
    }
}
