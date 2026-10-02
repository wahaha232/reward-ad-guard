package com.rewardadguard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventRecord::class, SessionRecord::class, AppStatsRecord::class],
    version = 1,
    exportSchema = false
)
abstract class RewardAdGuardDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao
    abstract fun sessionDao(): SessionDao
    abstract fun appStatsDao(): AppStatsDao

    companion object {
        private const val DB_NAME = "reward_ad_guard.db"

        @Volatile
        private var instance: RewardAdGuardDatabase? = null

        fun get(context: Context): RewardAdGuardDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RewardAdGuardDatabase::class.java,
                    DB_NAME
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
