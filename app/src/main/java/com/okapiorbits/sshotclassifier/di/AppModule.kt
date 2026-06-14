package com.okapiorbits.sshotclassifier.di

import android.content.Context
import androidx.room.Room
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "sshot_classifier.db")
            // Pre-release: no real user data to preserve, so destructive recreate is fine.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideScreenshotDao(db: AppDatabase): ScreenshotDao = db.screenshotDao()
}
