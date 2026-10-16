package com.aesprt.aquahub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePosMigrationTest {
    @Test
    fun v21MigrationPreservesOutboxAndQuarantinesUnversionedStockDeltas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE products (`id` INTEGER PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            """CREATE TABLE sync_queue (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `operationId` TEXT NOT NULL,
                                `resourceType` TEXT NOT NULL,
                                `resourceLocalId` INTEGER NOT NULL,
                                `resourceRemoteId` TEXT NOT NULL,
                                `stationLocalId` INTEGER,
                                `operation` TEXT NOT NULL,
                                `expectedPreviousStatus` TEXT,
                                `requestedStatus` TEXT,
                                `stockDelta` INTEGER,
                                `state` TEXT NOT NULL,
                                `retryCount` INTEGER NOT NULL,
                                `lastAttemptAt` INTEGER,
                                `lastErrorCode` TEXT,
                                `lastError` TEXT,
                                `createdAt` INTEGER NOT NULL
                            )""".trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO sync_queue (operationId,resourceType,resourceLocalId,resourceRemoteId,operation,stockDelta,state,retryCount,createdAt) " +
                    "VALUES ('legacy-stock-op','PRODUCT',4,'remote-product','STOCK_ADJUSTMENT',-3,'PENDING',0,100)",
            )
            db.execSQL(
                "INSERT INTO sync_queue (operationId,resourceType,resourceLocalId,resourceRemoteId,operation,state,retryCount,createdAt) " +
                    "VALUES ('customer-op','CUSTOMER',9,'remote-customer','UPSERT','PENDING',0,101)",
            )

            AquaHubDatabase.MIGRATION_21_22.migrate(db)

            db.query("SELECT operationId,state,lastErrorCode,stockDelta FROM sync_queue ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-stock-op", cursor.getString(0))
                assertEquals("QUARANTINED", cursor.getString(1))
                assertEquals("SYNC02_REVISION_REQUIRED", cursor.getString(2))
                assertEquals(-3, cursor.getInt(3))
                assertTrue(cursor.moveToNext())
                assertEquals("customer-op", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
            db.query("PRAGMA table_info(sync_queue)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("stockTarget" in names)
                assertTrue("expectedInventoryVersion" in names)
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='offline_pos_operations'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            helper.close()
        }
    }
}
