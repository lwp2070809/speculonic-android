package de.lwp2070809.speculonic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
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
    fun providePlaybackController(@ApplicationContext context: Context): de.lwp2070809.speculonic.playback.PlaybackController {
        return de.lwp2070809.speculonic.playback.PlaybackController.getInstance(context)
    }
}
