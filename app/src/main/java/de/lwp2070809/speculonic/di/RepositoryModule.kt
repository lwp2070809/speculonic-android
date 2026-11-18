package de.lwp2070809.speculonic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.CacheCleanupHelper
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LocalLyricsProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSubsonicRepository(
        @ApplicationContext context: Context,
        database: AppDatabase,
        musicDao: MusicDao,
        preferencesManager: PreferencesManager,
        localLyricsProvider: LocalLyricsProvider,
        cacheCleanupHelper: CacheCleanupHelper
    ): SubsonicRepository {
        return SubsonicRepository(
            context = context,
            database = database,
            musicDao = musicDao,
            preferencesManager = preferencesManager,
            localLyricsProvider = localLyricsProvider,
            cacheCleanupHelper = cacheCleanupHelper
        )
    }
}
