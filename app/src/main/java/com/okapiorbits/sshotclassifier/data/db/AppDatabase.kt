package com.okapiorbits.sshotclassifier.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity

@Database(
    entities = [
        ScreenshotEntity::class,
        TagEntity::class,
        OcrEntryEntity::class,
        EmbeddingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotDao
}
