package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.supabase.staff.SupabaseStaffDataSource
import com.aesprt.aquahub.domain.repository.StaffDirectory
import com.aesprt.aquahub.domain.repository.StaffMember
import com.aesprt.aquahub.domain.repository.StaffRepository

class SupabaseStaffRepository(
    private val supabase: SupabaseStaffDataSource,
) : StaffRepository {
    override suspend fun list(): StaffDirectory = supabase.list()
    override suspend fun create(name: String, phone: String, role: String, stationIds: List<String>): StaffMember =
        supabase.create(name, phone, role, stationIds)
    override suspend fun update(uid: String, name: String, phone: String, role: String, stationIds: List<String>): StaffMember =
        supabase.update(uid, name, phone, role, stationIds)
    override suspend fun delete(uid: String) {
        supabase.delete(uid)
    }
}
