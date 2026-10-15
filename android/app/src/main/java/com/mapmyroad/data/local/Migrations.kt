package com.mapmyroad.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations of [MapMyRoadDatabase] (CODING_STANDARDS.md section 3.10). Each one is covered by
 * `MigrationTest` in androidTest. Never edit a migration that has shipped: add a new one.
 */
object DatabaseMigrations {
    /**
     * v1 to v2 (M5-4): adds `upload_queue`. The SQL is what Room generates for [UploadQueueEntity] (see
     * `app/schemas/.../2.json`), so Room's schema validation after the migration passes.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_queue` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trip_id` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`next_attempt_at` INTEGER NOT NULL, " +
                        "`last_error` TEXT, " +
                        "FOREIGN KEY(`trip_id`) REFERENCES `trip`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_upload_queue_state_next_attempt_at` " +
                        "ON `upload_queue` (`state`, `next_attempt_at`)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_queue_trip_id` ON `upload_queue` (`trip_id`)")
            }
        }

    /** Every migration, in order, for `Room.databaseBuilder(...).addMigrations(*ALL)`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
