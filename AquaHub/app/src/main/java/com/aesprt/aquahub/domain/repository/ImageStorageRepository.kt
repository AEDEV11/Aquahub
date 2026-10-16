package com.aesprt.aquahub.domain.repository

interface ImageStorageRepository {
    suspend fun uploadProductImage(stationId: Long, sourceUri: String): Result<String>
    suspend fun uploadStationLogo(stationId: Long, sourceUri: String): Result<String>
    suspend fun uploadPaymentQr(stationId: Long, wallet: String, sourceUri: String): Result<String>
    suspend fun uploadDeliveryPaymentProof(stationId: Long, orderId: Long, sourceUri: String): Result<String>
    suspend fun deleteOwnedImage(downloadUrl: String): Result<Unit>
}
