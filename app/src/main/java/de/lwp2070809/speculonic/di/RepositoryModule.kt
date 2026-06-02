package de.lwp2070809.speculonic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSubsonicRepository(
        @ApplicationContext context: Context,
        musicDao: MusicDao,
        preferencesManager: PreferencesManager,
        okHttpClient: OkHttpClient
    ): SubsonicRepository {
        return SubsonicRepository(
            context = context,
            musicDao = musicDao,
            preferencesManager = preferencesManager,
            okHttpClient = okHttpClient
        )
    }
}
