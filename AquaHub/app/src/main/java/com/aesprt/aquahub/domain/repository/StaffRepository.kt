package com.aesprt.aquahub.domain.repository

data class StaffMember(
    val uid: String,
    val name: String,
    val phone: String,
    val role: String,
    val isActive: Boolean,
    val stationIds: List<String>
)

data class StaffDirectory(
    val members: List<StaffMember>,
    val staffLimit: Int? = null,
    val planCode: String? = null
)

interface StaffRepository {
    suspend fun list(): StaffDirectory
    suspend fun create(name: String, phone: String, role: String, stationIds: List<String>): StaffMember
    suspend fun update(uid: String, name: String, phone: String, role: String, stationIds: List<String>): StaffMember
    suspend fun delete(uid: String)
}
