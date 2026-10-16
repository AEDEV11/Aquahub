package com.aesprt.aquahub.data.local

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.domain.common.LocalTransactionRunner

class RoomTransactionRunner(private val database: AquaHubDatabase) : LocalTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
