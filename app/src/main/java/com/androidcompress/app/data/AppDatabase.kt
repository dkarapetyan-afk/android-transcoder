package com.androidcompress.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun toJobType(value: String): JobType = JobType.valueOf(value)
    @TypeConverter fun fromJobType(value: JobType): String = value.name
    @TypeConverter fun toJobStatus(value: String): JobStatus = JobStatus.valueOf(value)
    @TypeConverter fun fromJobStatus(value: JobStatus): String = value.name
}

@Dao
interface JobDao {
    @Query("SELECT * FROM compress_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CompressJob>>

    @Query("SELECT * FROM compress_jobs WHERE id = :id")
    fun observe(id: String): Flow<CompressJob?>

    @Query("SELECT * FROM compress_jobs WHERE id = :id")
    suspend fun get(id: String): CompressJob?

    @Query("SELECT * FROM compress_jobs ORDER BY createdAt DESC")
    suspend fun listAll(): List<CompressJob>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: CompressJob)

    @Update
    suspend fun update(job: CompressJob)

    @Query("DELETE FROM compress_jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM compress_jobs WHERE status = 'QUEUED' ORDER BY queuedAt ASC, createdAt ASC LIMIT 1")
    suspend fun nextQueued(): CompressJob?

    @Query("SELECT * FROM compress_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, queuedAt ASC, createdAt ASC")
    fun observeActive(): Flow<List<CompressJob>>

    @Query("SELECT * FROM compress_jobs WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun listActive(): List<CompressJob>

    @Query("UPDATE compress_jobs SET status = 'CANCELLED', finishedAt = :now WHERE status = 'QUEUED'")
    suspend fun cancelAllQueued(now: Long)
}

@Database(entities = [CompressJob::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "recording-compressor.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
