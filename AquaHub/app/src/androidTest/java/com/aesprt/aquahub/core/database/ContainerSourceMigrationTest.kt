package com.aesprt.aquahub.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContainerSourceMigrationTest {
    @Test
    fun addsNullableContainerChoiceAndKeepsExistingOrderItems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE `order_items` (
                                `id` INTEGER PRIMARY KEY NOT NULL,
                                `orderId` INTEGER NOT NULL,
                                `productName` TEXT NOT NULL,
                                `productType` TEXT NOT NULL,
                                `sizeLabel` TEXT NOT NULL,
                                `unitPrice` REAL NOT NULL,
                                `quantity` INTEGER NOT NULL,
                                `subtotal` REAL NOT NULL
                            )""".trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `order_items` (`id`, `orderId`, `productName`, `productType`, `sizeLabel`, `unitPrice`, `quantity`, `subtotal`) " +
                    "VALUES (1, 2, 'Purified refill', 'REFILL', '5 gal', 40, 1, 40)"
            )

            AquaHubDatabase.MIGRATION_20_21.migrate(db)

            db.query("SELECT `containerSource` FROM `order_items` WHERE `id` = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertNull(cursor.getString(0))
            }
            db.execSQL("UPDATE `order_items` SET `containerSource` = 'STATION_BORROW' WHERE `id` = 1")
            db.query("SELECT `containerSource` FROM `order_items` WHERE `id` = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("STATION_BORROW", cursor.getString(0))
            }
        } finally {
            helper.close()
        }
    }
}
