package com.okapiorbits.sshotclassifier.di

import android.content.Context
import androidx.room.Room
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.pipeline.CaptureDescriber
import com.okapiorbits.sshotclassifier.pipeline.vlm.CaptureDescriberRouter
import com.okapiorbits.sshotclassifier.pipeline.clip.CategoryEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipTextEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.TextEmbedder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds interface implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    /**
     * The router picks structured vs the experimental generative VLM per preference + device +
     * model availability, falling back to structured. See CaptureDescriberRouter.
     */
    @Binds
    abstract fun bindCaptureDescriber(impl: CaptureDescriberRouter): CaptureDescriber
}

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

    @Provides
    fun provideTextEmbedder(encoder: ClipTextEncoder): TextEmbedder = encoder

    @Provides
    fun provideLabelEmbedder(embedder: CategoryEmbedder): LabelEmbedder = embedder
}
