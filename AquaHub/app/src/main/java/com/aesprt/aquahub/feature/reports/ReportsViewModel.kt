package com.aesprt.aquahub.feature.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.export.*
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.StationRepository
import com.aesprt.aquahub.domain.repository.PaymentRepository
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import com.aesprt.aquahub.domain.usecase.analytics.GetFullBusinessAnalyticsUseCase
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.export.ExportReportUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ReportExportState { IDLE, GENERATING, SUCCESS, ERROR }

data class ReportsUiState(
    val selectedDateRange: DateRangeFilter = DateRangeFilter.LAST_7_DAYS,
    val customStartTimestamp: Long? = null,
    val customEndTimestamp: Long? = null,
    val selectedStationId: Long = 1L,
    val stationName: String = "Main Station",
    val isConsolidatedView: Boolean = false,
    val canAccessConsolidated: Boolean = false,
    val canAccessCustomerInsights: Boolean = false,
    val currentPlanCode: SubscriptionPlanCode = SubscriptionPlanCode.STARTER,
    val allowedFormats: Set<ReportFormat> = setOf(ReportFormat.CSV),
    val allowedReportTypes: Set<ReportType> = setOf(ReportType.SALES_AND_REVENUE, ReportType.ORDERS_LIST),
    val analytics: FullBusinessAnalytics? = null,
    val refundObligations: List<RefundObligationSummary> = emptyList(),
    val isLoadingRefundObligations: Boolean = false,
    val refundObligationsError: String? = null,
    val showExportDialog: Boolean = false,
    val isExporting: Boolean = false,
    val exportState: ReportExportState = ReportExportState.IDLE,
    val exportResult: ReportExportResult? = null,
    val exportSuccessMessage: String? = null,
    val exportErrorMessage: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class ReportsViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val stationRepository: StationRepository,
    private val paymentRepository: PaymentRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val getFullBusinessAnalyticsUseCase: GetFullBusinessAnalyticsUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val exportReportUseCase: ExportReportUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var refundLoadJob: Job? = null
    private var subscriptionObservationJob: Job? = null

    init {
        observeSubscriptionState()
    }

    fun retryLoad() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, analytics = null) }
        observeSubscriptionState()
    }

    private fun observeSubscriptionState() {
        subscriptionObservationJob?.cancel()
        subscriptionObservationJob = viewModelScope.launch {
            combine(activeStationContext.observeActiveStationId(), observeCurrentOwnerUseCase()) { activeId, owner ->
                activeId ?: owner?.stationId ?: 0L
            }.distinctUntilChanged()
                .catch { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Analytics access could not be loaded. Retry to try again.")
                    }
                }
                .collectLatest { stationId ->
                    if (stationId <= 0L) {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "No active station is ready for analytics.")
                        }
                        return@collectLatest
                    }
                    try {
                        subscriptionRepository.observeCurrentSubscription(stationId).collectLatest { subscription ->
                            try {
                                val station = stationRepository.getStation(stationId)
                                val consolidated = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CONSOLIDATED_REPORTS) is SubscriptionAccessResult.Allowed
                                val customerInsights = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CUSTOMER_INSIGHTS) is SubscriptionAccessResult.Allowed
                                val allowedFormats = ReportFormat.entries.filter { format ->
                                    val feature = when (format) {
                                        ReportFormat.CSV -> SubscriptionFeature.EXPORT_CSV
                                        ReportFormat.EXCEL -> SubscriptionFeature.EXPORT_EXCEL
                                        ReportFormat.PDF -> SubscriptionFeature.EXPORT_PDF
                                    }
                                    checkSubscriptionAccessUseCase(stationId, feature) is SubscriptionAccessResult.Allowed
                                }.toSet()
                                val allowedReportTypes = ReportType.entries.filter { type ->
                                    val feature = when (type) {
                                        ReportType.CUSTOMER_INSIGHTS -> SubscriptionFeature.CUSTOMER_INSIGHTS
                                        ReportType.DELIVERY_AND_RIDERS -> SubscriptionFeature.DELIVERY_ANALYTICS
                                        ReportType.FULL_BUSINESS_SUMMARY, ReportType.TOP_PRODUCTS -> SubscriptionFeature.ADVANCED_REPORTS
                                        ReportType.SALES_AND_REVENUE, ReportType.ORDERS_LIST -> SubscriptionFeature.BASIC_REPORTS
                                    }
                                    checkSubscriptionAccessUseCase(stationId, feature) is SubscriptionAccessResult.Allowed
                                }.toSet()
                                _uiState.update {
                                    it.copy(selectedStationId = stationId, stationName = station?.name ?: "Water Station",
                                        currentPlanCode = subscription?.planCode ?: SubscriptionPlanCode.STARTER,
                                        allowedFormats = allowedFormats, allowedReportTypes = allowedReportTypes,
                                        canAccessConsolidated = consolidated, canAccessCustomerInsights = customerInsights,
                                        isConsolidatedView = it.isConsolidatedView && consolidated,
                                        analytics = null, isLoading = true, errorMessage = null)
                                }
                                loadAnalytics()
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                _uiState.update {
                                    it.copy(isLoading = false, analytics = null, errorMessage = "Analytics access could not be loaded. Retry to try again.")
                                }
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        _uiState.update {
                            it.copy(isLoading = false, analytics = null, errorMessage = "Analytics access could not be loaded. Retry to try again.")
                        }
                    }
                }
        }
    }

    fun selectDateRange(filter: DateRangeFilter) {
        _uiState.update {
            it.copy(selectedDateRange = filter, customStartTimestamp = null, customEndTimestamp = null, isLoading = true)
        }
        loadAnalytics()
    }

    fun selectCustomDateRange(startTimestamp: Long, endTimestamp: Long) {
        _uiState.update {
            it.copy(
                selectedDateRange = DateRangeFilter.CUSTOM,
                customStartTimestamp = startTimestamp,
                customEndTimestamp = endTimestamp,
                isLoading = true
            )
        }
        loadAnalytics()
    }

    fun toggleConsolidatedView(enabled: Boolean) {
        _uiState.update { it.copy(isConsolidatedView = enabled && it.canAccessConsolidated, isLoading = true) }
        loadAnalytics()
    }

    fun resetConsolidatedView() {
        loadJob?.cancel()
        _uiState.update {
            it.copy(isConsolidatedView = false, analytics = null, isLoading = true)
        }
        loadAnalytics()
    }

    fun openExportDialog() {
        _uiState.update { it.copy(showExportDialog = true, exportState = ReportExportState.IDLE, exportResult = null, exportSuccessMessage = null, exportErrorMessage = null) }
    }

    fun closeExportDialog() {
        _uiState.update { it.copy(showExportDialog = false, exportState = ReportExportState.IDLE, exportResult = null, exportSuccessMessage = null, exportErrorMessage = null) }
    }

    fun exportReport(
        context: Context,
        reportType: ReportType,
        format: ReportFormat,
        dateRange: DateRangeFilter
    ) {
        val currentState = _uiState.value
        if (currentState.isExporting || currentState.selectedStationId <= 0L) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportState = ReportExportState.GENERATING, exportSuccessMessage = null, exportErrorMessage = null) }
            try {
                val analytics = getFullBusinessAnalyticsUseCase(
                    stationId = currentState.selectedStationId,
                    dateRange = dateRange,
                    isConsolidatedRequested = currentState.isConsolidatedView,
                    customStartTimestamp = currentState.customStartTimestamp,
                    customEndTimestamp = currentState.customEndTimestamp
                )

                val request = ReportExportRequest(
                stationId = currentState.selectedStationId,
                stationName = currentState.stationName,
                reportType = reportType,
                format = format,
                dateRange = dateRange,
                    analytics = analytics
                )

                val result = exportReportUseCase(context, request)
            result.fold(
                onSuccess = { exportRes ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportState = ReportExportState.SUCCESS,
                            exportResult = exportRes,
                            exportSuccessMessage = "${reportType.displayName} downloaded successfully"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportState = ReportExportState.ERROR,
                            exportErrorMessage = error.message ?: "Failed to generate ${reportType.displayName.lowercase()}"
                        )
                    }
                }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExporting = false, exportState = ReportExportState.ERROR, exportErrorMessage = e.message ?: "Failed to generate ${reportType.displayName.lowercase()}")
                }
            }
        }
    }

    private fun loadAnalytics() {
        val currentState = _uiState.value
        val stationId = currentState.selectedStationId
        val range = currentState.selectedDateRange
        val isConsolidated = currentState.isConsolidatedView

        loadRefundObligations(currentState)

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val fullAnalytics = getFullBusinessAnalyticsUseCase(
                    stationId = stationId,
                    dateRange = range,
                    isConsolidatedRequested = isConsolidated,
                    customStartTimestamp = currentState.customStartTimestamp,
                    customEndTimestamp = currentState.customEndTimestamp
                )
                _uiState.update {
                    it.copy(
                        analytics = fullAnalytics,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Error loading analytics"
                    )
                }
            }
        }
    }

    fun refreshRefundObligations() = loadRefundObligations(_uiState.value)

    private fun loadRefundObligations(state: ReportsUiState) {
        if (state.selectedStationId <= 0L) return
        refundLoadJob?.cancel()
        refundLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingRefundObligations = true, refundObligationsError = null)
            }
            try {
                val (startAt, endAt) = state.selectedDateRange.calculateTimestamps(
                    state.customStartTimestamp,
                    state.customEndTimestamp
                )
                val report = paymentRepository.getRefundObligationReport(
                    stationId = state.selectedStationId,
                    startAt = startAt,
                    endAt = endAt
                )
                _uiState.update {
                    it.copy(
                        refundObligations = report,
                        isLoadingRefundObligations = false,
                        refundObligationsError = null
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingRefundObligations = false,
                        refundObligationsError = e.message ?: "Refund obligations could not be loaded"
                    )
                }
            }
        }
    }
}
