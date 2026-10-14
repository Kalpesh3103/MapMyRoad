package com.mapmyroad.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * On-device database (DATABASE_SCHEMA.md Part B). `upload_queue` arrives in v2 with schema version 2.
 *
 * Every future version needs a [androidx.room.migration.Migration] (or an AutoMigration) and a
 * `MigrationTestHelper` test in androidTest, and the exported JSON schema in `app/schemas` must be committed
 * (CODING_STANDARDS.md section 3.10). Destructive migrations are not allowed.
 */
@Database(
    entities = [
        TripEntity::class,
        TripPointEntity::class,
        HazardEventLocalEntity::class,
        SegmentSampleLocalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MapMyRoadDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        const val NAME = "mapmyroad.db"
    }
}
