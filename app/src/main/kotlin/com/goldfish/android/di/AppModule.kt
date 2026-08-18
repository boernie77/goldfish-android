package com.goldfish.android.di

import android.content.Context
import androidx.room.Room
import com.goldfish.android.data.api.ApiClientProvider
import com.goldfish.android.data.api.PersistentCookieJar
import com.goldfish.android.data.local.AppDatabase
import com.goldfish.android.data.local.LOCAL_MIGRATION_1_2
import com.goldfish.android.data.local.LOCAL_MIGRATION_2_3
import com.goldfish.android.data.local.LOCAL_MIGRATION_3_4
import com.goldfish.android.data.local.LOCAL_MIGRATION_4_5
import com.goldfish.android.data.local.LocalAppDatabase
import com.goldfish.android.data.local.MIGRATION_1_2
import com.goldfish.android.data.local.MIGRATION_2_3
import com.goldfish.android.data.local.MIGRATION_3_4
import com.goldfish.android.data.local.MIGRATION_4_5
import com.goldfish.android.data.local.MIGRATION_5_6
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "goldfish.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    /**
     * Separate Room-DB fuer lokale (on-device) Bibliotheken. Eigener Pfad,
     * eigene Schema-Version, keine Migrationen vom Goldfish-Cache.
     */
    @Provides
    @Singleton
    fun provideLocalAppDatabase(@ApplicationContext context: Context): LocalAppDatabase {
        return Room.databaseBuilder(
            context,
            LocalAppDatabase::class.java,
            "goldfish-local.db"
        )
            .addMigrations(LOCAL_MIGRATION_1_2, LOCAL_MIGRATION_2_3, LOCAL_MIGRATION_3_4, LOCAL_MIGRATION_4_5)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar {
        return PersistentCookieJar(context)
    }

    @Provides
    @Singleton
    fun provideApiClientProvider(
        @ApplicationContext context: Context,
        cookieJar: PersistentCookieJar
    ): ApiClientProvider {
        return ApiClientProvider(context, cookieJar)
    }
}
