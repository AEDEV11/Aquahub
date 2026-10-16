package com.aesprt.aquahub.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aesprt.aquahub.core.common.Constants
import com.aesprt.aquahub.data.local.dao.*
import com.aesprt.aquahub.data.local.entity.*

@Database(
    entities = [
        StationEntity::class,
        OwnerEntity::class,
        CustomerEntity::class,
        CustomerAddressEntity::class,
        ProductEntity::class,
        RiderEntity::class,
        OrderEntity::class,
        OrderItemEntity::class,
        ContainerEntity::class,
        ContainerTransactionEntity::class,
        PaymentEntity::class,
        SubscriptionPlanEntity::class,
        SubscriptionEntity::class,
        PlanFeatureEntity::class,
        PlanLimitEntity::class,
        SubscriptionHistoryEntity::class,
        SyncMetadataEntity::class,
        SyncQueueEntity::class,
        SyncCursorEntity::class,
        ExpenseEntity::class,
        PaymentRefundEntity::class,
        OfflinePosGrantEntity::class,
        OfflinePosLeaseEntity::class,
        OfflinePosLeaseItemEntity::class,
        OfflinePosOperationEntity::class,
        OfflinePosOperationLineEntity::class,
        OfflinePosReconciliationEventEntity::class,
    ],
    version =  22,
    exportSchema = false
)
abstract class AquaHubDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
    abstract fun ownerDao(): OwnerDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun riderDao(): RiderDao
    abstract fun orderDao(): OrderDao
    abstract fun orderItemDao(): OrderItemDao
    abstract fun containerDao(): ContainerDao
    abstract fun containerTransactionDao(): ContainerTransactionDao
    abstract fun paymentDao(): PaymentDao
    abstract fun paymentRefundDao(): PaymentRefundDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionPlanDao(): SubscriptionPlanDao
    abstract fun subscriptionHistoryDao(): SubscriptionHistoryDao
    abstract fun syncDao(): SyncDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun offlinePosDao(): OfflinePosDao

    companion object {
        fun create(context: Context): AquaHubDatabase {
            return Room.databaseBuilder(context, AquaHubDatabase::class.java, Constants.DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seeding happens after build — resolved after DI graph is constructed
                    }
                })
                .build()
        }

        /** Version 2 introduced the subscription cache. This migration replaces the old destructive fallback. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `subscription_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `monthlyPrice` REAL NOT NULL, `annualPrice` REAL NOT NULL, `isPopular` INTEGER NOT NULL, `trialDays` INTEGER NOT NULL, `ctaText` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscription_plans_code` ON `subscription_plans` (`code`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stationId` INTEGER NOT NULL, `planId` INTEGER NOT NULL, `status` TEXT NOT NULL, `billingCycle` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `currentPeriodStart` INTEGER NOT NULL, `currentPeriodEnd` INTEGER NOT NULL, `trialStartedAt` INTEGER, `trialEndsAt` INTEGER, `cancelledAt` INTEGER, `expiresAt` INTEGER, `gracePeriodEndsAt` INTEGER, `autoRenew` INTEGER NOT NULL, `cancelAtPeriodEnd` INTEGER NOT NULL, `pendingPlanId` INTEGER, `pendingPlanEffectiveAt` INTEGER, `provider` TEXT, `externalSubscriptionId` TEXT, `externalCustomerId` TEXT, `lastPaymentId` TEXT, `currency` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`stationId`) REFERENCES `stations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`planId`) REFERENCES `subscription_plans`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_stationId` ON `subscriptions` (`stationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_planId` ON `subscriptions` (`planId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_status` ON `subscriptions` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `plan_features` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `feature` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `limitValue` INTEGER, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`planId`) REFERENCES `subscription_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_features_planId` ON `plan_features` (`planId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_features_planId_feature` ON `plan_features` (`planId`, `feature`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `plan_limits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `limitType` TEXT NOT NULL, `maxValue` INTEGER NOT NULL, FOREIGN KEY(`planId`) REFERENCES `subscription_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_limits_planId` ON `plan_limits` (`planId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_limits_planId_limitType` ON `plan_limits` (`planId`, `limitType`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `subscription_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stationId` INTEGER NOT NULL, `subscriptionId` INTEGER NOT NULL, `previousPlanId` INTEGER, `newPlanId` INTEGER, `eventType` TEXT NOT NULL, `effectiveAt` INTEGER NOT NULL, `previousStatus` TEXT, `newStatus` TEXT, `notes` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`stationId`) REFERENCES `stations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_history_stationId` ON `subscription_history` (`stationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_history_subscriptionId` ON `subscription_history` (`subscriptionId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `placeId` TEXT")
                db.execSQL("ALTER TABLE `station_owners` ADD COLUMN `firebaseUid` TEXT")
                db.execSQL("ALTER TABLE `station_owners` ADD COLUMN `businessId` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_metadata` (`resourceType` TEXT NOT NULL, `localId` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `stationLocalId` INTEGER, `localVersion` INTEGER NOT NULL, `remoteVersion` INTEGER NOT NULL, `lastLocalUpdatedAt` INTEGER NOT NULL, `lastServerUpdatedAt` INTEGER, `lastSyncedAt` INTEGER, `deletedAt` INTEGER, PRIMARY KEY(`resourceType`, `localId`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_metadata_resourceType_remoteId` ON `sync_metadata` (`resourceType`, `remoteId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operationId` TEXT NOT NULL, `resourceType` TEXT NOT NULL, `resourceLocalId` INTEGER NOT NULL, `resourceRemoteId` TEXT NOT NULL, `stationLocalId` INTEGER, `operation` TEXT NOT NULL, `expectedPreviousStatus` TEXT, `requestedStatus` TEXT, `state` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `lastAttemptAt` INTEGER, `lastErrorCode` TEXT, `lastError` TEXT, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_operationId` ON `sync_queue` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_state_createdAt` ON `sync_queue` (`state`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_resourceType_resourceLocalId` ON `sync_queue` (`resourceType`, `resourceLocalId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_stationLocalId` ON `sync_queue` (`stationLocalId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_cursors` (`businessId` TEXT NOT NULL, `stationRemoteId` TEXT NOT NULL, `resourceType` TEXT NOT NULL, `lastServerUpdatedAt` INTEGER NOT NULL, `initialSyncComplete` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`businessId`, `stationRemoteId`, `resourceType`))")
            }
        }

        /** Version 4 retains customer-selected delivery coordinates for rider navigation. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `deliveryLatitude` REAL")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `deliveryLongitude` REAL")
            }
        }

        /** Version 5 adds deliveryMode to orders and online ordering fields to stations. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `deliveryMode` TEXT NOT NULL DEFAULT 'DELIVERY'")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `isAcceptingOnlineOrders` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `deliveryRadiusKm` REAL")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `deliveryFeeCentavos` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `estimatedPreparationMinutes` INTEGER NOT NULL DEFAULT 30")
            }
        }

        /** Version 6 adds the published customer-facing business rules text and version. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `businessRulesText` TEXT")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `businessRulesVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 7 stores relative product-stock changes in the durable outbox. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_queue` ADD COLUMN `stockDelta` INTEGER")
            }
        }

        /** Version 8 distinguishes archived products from temporarily unavailable products. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `isActive` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Version 9 persists the owner's manual station open/closed override. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `manualOpenOverride` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 10 stores the Firebase business-member role for staff access control. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `station_owners` ADD COLUMN `role` TEXT NOT NULL DEFAULT 'OWNER'")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `publicStationCode` TEXT")
            }
        }

        /** Version 12 persists station-level payment activation and manual wallet details. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `cashPaymentEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `codPaymentEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `gcashPaymentEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `gcashAccountName` TEXT")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `gcashAccountNumber` TEXT")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `mayaPaymentEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `mayaAccountName` TEXT")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `mayaAccountNumber` TEXT")
            }
        }

        /** Version 13 stores owner-uploaded GCash and Maya QR image URLs. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `gcashQrImagePath` TEXT")
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `mayaQrImagePath` TEXT")
            }
        }

        /** Version 14 adds plan-gated, product-level promotional pricing. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionLabel` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionType` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionPercentBps` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionalPrice` REAL")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionMinimumQuantity` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionStartsAt` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionEndsAt` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `promotionIsActive` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 15 adds the offline-first station operating-expense ledger. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stationId` INTEGER NOT NULL, `amountCentavos` INTEGER NOT NULL, `category` TEXT NOT NULL, `description` TEXT NOT NULL, `vendor` TEXT, `paymentMethod` TEXT NOT NULL, `referenceNumber` TEXT, `notes` TEXT, `incurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_stationId` ON `expenses` (`stationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_incurredAt` ON `expenses` (`incurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_stationId_incurredAt` ON `expenses` (`stationId`, `incurredAt`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stations` ADD COLUMN `isActive` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Version 17 stores Supabase payment UUIDs and provider metadata in the local cache. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `remoteId` TEXT")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `firebaseId` TEXT")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `provider` TEXT")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `providerTransactionId` TEXT")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `metadataJson` TEXT")
                db.execSQL("ALTER TABLE `payments` ADD COLUMN `operationId` TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payments_remoteId` ON `payments` (`remoteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_operationId` ON `payments` (`operationId`)")
            }
        }

        /** Version 18 stores the Supabase identity and object path for expenses. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `remoteId` TEXT")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `firebaseId` TEXT")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `organizationRemoteId` TEXT")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `createdByRemoteId` TEXT")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `receiptPath` TEXT")
                db.execSQL("ALTER TABLE `expenses` ADD COLUMN `operationId` TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expenses_remoteId` ON `expenses` (`remoteId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expenses_operationId` ON `expenses` (`operationId`)")
            }
        }

        /** Version 19 persists the customer-visible reason for an order cancellation. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `cancellationReason` TEXT")
            }
        }

        /** Version 20 stores server-confirmed refund history and durable refund retry keys. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `payment_refunds` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `remoteId` TEXT, `orderId` INTEGER NOT NULL, `originalPaymentId` INTEGER, `operationId` TEXT NOT NULL, `completionOperationId` TEXT, `failureOperationId` TEXT, `amountCentavos` INTEGER NOT NULL, `paymentMethod` TEXT NOT NULL, `status` TEXT NOT NULL, `reason` TEXT NOT NULL, `externalReference` TEXT, `failureReason` TEXT, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_refunds_remoteId` ON `payment_refunds` (`remoteId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_refunds_operationId` ON `payment_refunds` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_refunds_orderId` ON `payment_refunds` (`orderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_refunds_status` ON `payment_refunds` (`status`)")
            }
        }

        /** Version 21 stores the refill container choice for web guest orders. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `order_items` ADD COLUMN `containerSource` TEXT")
            }
        }

        /** Version 22 adds durable offline POS journal and revision-safe stock intent. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_queue` ADD COLUMN `stockTarget` INTEGER")
                db.execSQL("ALTER TABLE `sync_queue` ADD COLUMN `expectedInventoryVersion` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `inventoryVersion` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `sync_queue` SET `state` = 'QUARANTINED', `lastErrorCode` = 'SYNC02_REVISION_REQUIRED', `lastError` = 'Preserved for explicit review; legacy relative stock intent cannot be safely replayed.' WHERE `stockDelta` IS NOT NULL AND `state` IN ('PENDING', 'RETRY', 'FAILED', 'IN_PROGRESS')")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_grants` (`grantId` TEXT NOT NULL, `profileUid` TEXT NOT NULL, `organizationId` TEXT NOT NULL, `stationId` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `signedGrant` TEXT NOT NULL, `issuedAtMs` INTEGER NOT NULL, `expiresAtMs` INTEGER NOT NULL, `serverTimeAnchorMs` INTEGER NOT NULL, `elapsedRealtimeAnchorMs` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `storedAtMs` INTEGER NOT NULL, PRIMARY KEY(`grantId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_grants_profileUid_organizationId_stationId_deviceId` ON `offline_pos_grants` (`profileUid`, `organizationId`, `stationId`, `deviceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_grants_expiresAtMs` ON `offline_pos_grants` (`expiresAtMs`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_leases` (`leaseId` TEXT NOT NULL, `grantId` TEXT NOT NULL, `profileUid` TEXT NOT NULL, `organizationId` TEXT NOT NULL, `stationId` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `signedLease` TEXT NOT NULL, `issuedAtMs` INTEGER NOT NULL, `expiresAtMs` INTEGER NOT NULL, `catalogSnapshotAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `storedAtMs` INTEGER NOT NULL, PRIMARY KEY(`leaseId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_leases_grantId` ON `offline_pos_leases` (`grantId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_leases_organizationId_stationId_deviceId` ON `offline_pos_leases` (`organizationId`, `stationId`, `deviceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_leases_expiresAtMs` ON `offline_pos_leases` (`expiresAtMs`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_lease_items` (`leaseId` TEXT NOT NULL, `productLocalId` INTEGER NOT NULL, `productRemoteId` TEXT NOT NULL, `quantityReserved` INTEGER NOT NULL, `quantityConsumed` INTEGER NOT NULL, `quantityReleased` INTEGER NOT NULL, `productName` TEXT NOT NULL, `productType` TEXT NOT NULL, `sizeLabel` TEXT NOT NULL, `unitPriceCentavos` INTEGER NOT NULL, `productUpdatedAtMs` INTEGER NOT NULL, `inventoryVersion` INTEGER NOT NULL, PRIMARY KEY(`leaseId`, `productRemoteId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_lease_items_productLocalId` ON `offline_pos_lease_items` (`productLocalId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_operations` (`operationId` TEXT NOT NULL, `profileUid` TEXT NOT NULL, `organizationId` TEXT NOT NULL, `stationId` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `grantId` TEXT NOT NULL, `leaseId` TEXT NOT NULL, `localOrderId` INTEGER, `transactionAtMs` INTEGER NOT NULL, `elapsedRealtimeAtMs` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, `totalCentavos` INTEGER NOT NULL, `cashReceivedCentavos` INTEGER NOT NULL, `changeCentavos` INTEGER NOT NULL, `payloadDigest` TEXT NOT NULL, `canonicalPayload` TEXT NOT NULL, `deviceSignature` TEXT NOT NULL, `state` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `nextAttemptAtMs` INTEGER, `lastErrorCode` TEXT, `lastError` TEXT, `remoteOrderId` TEXT, `createdAtMs` INTEGER NOT NULL, `reconciledAtMs` INTEGER, PRIMARY KEY(`operationId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_operations_stationId_transactionAtMs` ON `offline_pos_operations` (`stationId`, `transactionAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_operations_state_nextAttemptAtMs` ON `offline_pos_operations` (`state`, `nextAttemptAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_operations_leaseId` ON `offline_pos_operations` (`leaseId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_operation_lines` (`operationId` TEXT NOT NULL, `lineNumber` INTEGER NOT NULL, `productLocalId` INTEGER NOT NULL, `productRemoteId` TEXT NOT NULL, `productName` TEXT NOT NULL, `productType` TEXT NOT NULL, `sizeLabel` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `unitPriceCentavos` INTEGER NOT NULL, `lineTotalCentavos` INTEGER NOT NULL, PRIMARY KEY(`operationId`, `lineNumber`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_operation_lines_productRemoteId` ON `offline_pos_operation_lines` (`productRemoteId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pos_reconciliation_events` (`eventId` TEXT NOT NULL, `operationId` TEXT, `leaseId` TEXT, `stationId` INTEGER NOT NULL, `actorUid` TEXT NOT NULL, `eventType` TEXT NOT NULL, `reason` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_reconciliation_events_operationId` ON `offline_pos_reconciliation_events` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_reconciliation_events_leaseId` ON `offline_pos_reconciliation_events` (`leaseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pos_reconciliation_events_stationId_createdAtMs` ON `offline_pos_reconciliation_events` (`stationId`, `createdAtMs`)")
            }
        }

    }
}
