package com.aesprt.aquahub.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.repository.CustomerRepository
import com.aesprt.aquahub.domain.usecase.customer.UpdateCustomerUseCase
import com.aesprt.aquahub.sync.SupabaseSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * Debug-only, disposable-record harness for migration validation.
 * It rejects every customer except the explicit migration-test name prefix.
 */
class MigrationValidationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive action=${intent.action}")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    MigrationValidationConfig.ACTION_SET_MIRROR_FAILURE -> {
                        MigrationValidationConfig.setForceCustomerMirrorFailure(
                            intent.getBooleanExtra(MigrationValidationConfig.EXTRA_ENABLED, false)
                        )
                        log("mirror_failure=${intent.getBooleanExtra(MigrationValidationConfig.EXTRA_ENABLED, false)}")
                    }
                    MigrationValidationConfig.ACTION_CREATE_CUSTOMER -> createCustomer()
                    MigrationValidationConfig.ACTION_UPDATE_CUSTOMER -> updateCustomer(intent)
                    MigrationValidationConfig.ACTION_ARCHIVE_CUSTOMER -> archiveCustomer(intent)
                    MigrationValidationConfig.ACTION_SYNC_NOW -> syncNow()
                }
            } catch (error: Exception) {
                log("status=ERROR type=${error::class.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun createCustomer() {
        val owner = GlobalContext.get().get<ObserveCurrentOwnerUseCase>().invoke().first()
            ?: error("No authenticated owner")
        val customer = Customer(
            stationId = owner.stationId,
            name = "Migration test customer",
            phone = "09000000002"
        )
        val id = GlobalContext.get().get<CustomerRepository>().insertCustomer(customer)
        log("status=CREATED local_id=$id station_id=${owner.stationId}")
    }

    private suspend fun updateCustomer(intent: Intent) {
        val id = intent.getLongExtra(MigrationValidationConfig.EXTRA_CUSTOMER_ID, -1L)
        val repository = GlobalContext.get().get<CustomerRepository>()
        val customer = requireDisposable(repository.getCustomerById(id))
        val update = GlobalContext.get().get<UpdateCustomerUseCase>()
        val result = update(customer.copy(
            name = "Migration test customer updated",
            notes = intent.getStringExtra(MigrationValidationConfig.EXTRA_NOTES) ?: "migration-validation"
        ))
        check(result.isSuccess) { "update rejected" }
        log("status=UPDATED local_id=$id")
    }

    private suspend fun archiveCustomer(intent: Intent) {
        val id = intent.getLongExtra(MigrationValidationConfig.EXTRA_CUSTOMER_ID, -1L)
        val repository = GlobalContext.get().get<CustomerRepository>()
        requireDisposable(repository.getCustomerById(id))
        repository.deleteCustomer(id)
        log("status=ARCHIVED local_id=$id")
    }

    private suspend fun syncNow() {
        val summary = GlobalContext.get().get<SupabaseSyncCoordinator>().sync()
        log("status=SYNC pushed=${summary.pushed} retryable=${summary.retryableFailures} permanent=${summary.permanentFailures}")
    }

    private fun requireDisposable(customer: Customer?): Customer {
        require(customer != null && customer.name.startsWith("Migration test")) {
            "Only disposable Migration test customers are allowed"
        }
        return customer
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "AquaHubMigrationValidation"
    }
}
