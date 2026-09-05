package com.fakegps.mocklocation.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fakegps.mocklocation.automation.data.*

@Database(
    entities = [
        FavoriteLocation::class,
        SearchHistoryItem::class,
        SavedRoute::class,
        MockLocationHistory::class,
        MockRouteHistory::class,
        ScheduleEntity::class,
        ScheduleStepEntity::class,
        WifiTriggerEntity::class,
        AutomationSettingsEntity::class,
        AutomationLogEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedRouteDao(): SavedRouteDao
    abstract fun mockHistoryDao(): MockHistoryDao

    // Automation DAOs
    abstract fun scheduleDao(): ScheduleDao
    abstract fun wifiTriggerDao(): WifiTriggerDao
    abstract fun automationSettingsDao(): AutomationSettingsDao
    abstract fun automationLogDao(): AutomationLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Table: automation_schedules
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `automation_schedules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `recurrenceType` TEXT NOT NULL,
                        `recurrenceConfig` TEXT NOT NULL,
                        `loop` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastTriggeredAt` INTEGER,
                        `nextTriggerAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Table: automation_schedule_steps
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `automation_schedule_steps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `scheduleId` INTEGER NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` INTEGER NOT NULL,
                        `triggerOffsetMinutes` INTEGER NOT NULL,
                        FOREIGN KEY(`scheduleId`) REFERENCES `automation_schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_schedule_steps_scheduleId` ON `automation_schedule_steps` (`scheduleId`)")

                // Table: automation_wifi_triggers
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `automation_wifi_triggers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ssid` TEXT NOT NULL,
                        `triggerType` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Table: automation_settings (singleton)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `automation_settings` (
                        `id` INTEGER PRIMARY KEY NOT NULL,
                        `scheduledAutomationEnabled` INTEGER NOT NULL,
                        `wifiTriggersEnabled` INTEGER NOT NULL,
                        `motionSyncEnabled` INTEGER NOT NULL,
                        `terrainLockEnabled` INTEGER NOT NULL,
                        `terrainRestrictedEnabled` INTEGER NOT NULL,
                        `terrainSearchRadiusMeters` REAL NOT NULL,
                        `terrainAllowUnmapped` INTEGER NOT NULL,
                        `jitterMinutes` INTEGER NOT NULL,
                        `quietHoursEnabled` INTEGER NOT NULL,
                        `quietHoursStartMinute` INTEGER NOT NULL,
                        `quietHoursEndMinute` INTEGER NOT NULL,
                        `quietHoursMode` TEXT NOT NULL,
                        `batteryGuardEnabled` INTEGER NOT NULL,
                        `batteryThresholdPercent` INTEGER NOT NULL,
                        `batteryResumePercent` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO `automation_settings` (
                        `id`, `scheduledAutomationEnabled`, `wifiTriggersEnabled`, `motionSyncEnabled`, `terrainLockEnabled`,
                        `terrainRestrictedEnabled`, `terrainSearchRadiusMeters`, `terrainAllowUnmapped`, `jitterMinutes`,
                        `quietHoursEnabled`, `quietHoursStartMinute`, `quietHoursEndMinute`, `quietHoursMode`,
                        `batteryGuardEnabled`, `batteryThresholdPercent`, `batteryResumePercent`
                    ) VALUES (1, 0, 0, 0, 1, 0, 25.0, 1, 4, 0, 60, 360, 'DELAY', 1, 15, 20)
                """.trimIndent())

                // Table: automation_logs
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `automation_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source` TEXT NOT NULL,
                        `targetSummary` TEXT NOT NULL,
                        `details` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mock_location_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            seedDefaults(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            seedDefaults(db)
                        }

                        private fun seedDefaults(db: SupportSQLiteDatabase) {
                            try {
                                db.execSQL("""
                                    INSERT OR IGNORE INTO `automation_settings` (
                                        `id`, `scheduledAutomationEnabled`, `wifiTriggersEnabled`, `motionSyncEnabled`, `terrainLockEnabled`,
                                        `terrainRestrictedEnabled`, `terrainSearchRadiusMeters`, `terrainAllowUnmapped`, `jitterMinutes`,
                                        `quietHoursEnabled`, `quietHoursStartMinute`, `quietHoursEndMinute`, `quietHoursMode`,
                                        `batteryGuardEnabled`, `batteryThresholdPercent`, `batteryResumePercent`
                                    ) VALUES (1, 0, 0, 0, 1, 0, 25.0, 1, 4, 0, 60, 360, 'DELAY', 1, 15, 20)
                                """.trimIndent())
                            } catch (ignored: Exception) {}
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
