package ch.lkmc.kararead.di

import android.content.Context
import androidx.room.Room
import ch.lkmc.kararead.data.local.CachedArticleDao
import ch.lkmc.kararead.data.local.KararreadDatabase
import ch.lkmc.kararead.data.local.PendingOpDao
import ch.lkmc.kararead.data.local.ReadingProgressDao
import ch.lkmc.kararead.data.local.ReadingStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KararreadDatabase =
        Room.databaseBuilder(context, KararreadDatabase::class.java, KararreadDatabase.NAME)
            .addMigrations(
                KararreadDatabase.MIGRATION_2_3,
                KararreadDatabase.MIGRATION_3_4,
                KararreadDatabase.MIGRATION_4_5,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideReadingProgressDao(db: KararreadDatabase): ReadingProgressDao = db.readingProgressDao()

    @Provides
    fun provideCachedArticleDao(db: KararreadDatabase): CachedArticleDao = db.cachedArticleDao()

    @Provides
    fun provideReadingStatsDao(db: KararreadDatabase): ReadingStatsDao = db.readingStatsDao()

    @Provides
    fun providePendingOpDao(db: KararreadDatabase): PendingOpDao = db.pendingOpDao()
}
