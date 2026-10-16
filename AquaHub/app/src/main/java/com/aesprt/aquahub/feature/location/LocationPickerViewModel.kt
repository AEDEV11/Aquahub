package com.aesprt.aquahub.feature.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.GeoPoint
import com.aesprt.aquahub.domain.model.LocationSuggestion
import com.aesprt.aquahub.domain.model.StationLocation
import com.aesprt.aquahub.domain.usecase.location.GetCurrentDeviceLocationUseCase
import com.aesprt.aquahub.domain.usecase.location.GetPlaceDetailsUseCase
import com.aesprt.aquahub.domain.usecase.location.ReverseGeocodeUseCase
import com.aesprt.aquahub.domain.usecase.location.SearchLocationsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LocationPickerUiState(
    val query: String = "",
    val suggestions: List<LocationSuggestion> = emptyList(),
    val isSearching: Boolean = false,
    val selectedLocation: StationLocation? = null,
    val currentCoordinates: GeoPoint = GeoPoint(8.4542, 124.6319), // Default to Cagayan de Oro
    val isReverseGeocoding: Boolean = false,
    val isLocatingDevice: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LocationPickerViewModel(
    private val searchLocationsUseCase: SearchLocationsUseCase,
    private val getPlaceDetailsUseCase: GetPlaceDetailsUseCase,
    private val reverseGeocodeUseCase: ReverseGeocodeUseCase,
    private val getCurrentDeviceLocationUseCase: GetCurrentDeviceLocationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationPickerUiState())
    val uiState: StateFlow<LocationPickerUiState> = _uiState.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(350)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.trim().length < 2) {
                        _uiState.update { it.copy(isSearching = false, suggestions = emptyList()) }
                        flowOf(Result.success(emptyList()))
                    } else {
                        _uiState.update { it.copy(isSearching = true) }
                        flow { emit(searchLocationsUseCase(query)) }
                    }
                }
                .collect { result ->
                    result.fold(
                        onSuccess = { list ->
                            _uiState.update {
                                it.copy(
                                    suggestions = list,
                                    isSearching = false,
                                    errorMessage = null
                                )
                            }
                        },
                        onFailure = {
                            _uiState.update {
                                it.copy(
                                    suggestions = emptyList(),
                                    isSearching = false
                                )
                            }
                        }
                    )
                }
        }
    }

    fun initializeLocation(initialAddress: String?, initialLat: Double?, initialLng: Double?) {
        val lat = if (initialLat != null && initialLat in -90.0..90.0) initialLat else 8.4542
        val lng = if (initialLng != null && initialLng in -180.0..180.0) initialLng else 124.6319

        val location = StationLocation(
            formattedAddress = initialAddress?.ifBlank { "Water Station Location" } ?: "Water Station Location",
            latitude = lat,
            longitude = lng
        )

        _uiState.update {
            it.copy(
                selectedLocation = location,
                currentCoordinates = GeoPoint(lat, lng),
                query = initialAddress ?: ""
            )
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        searchQueryFlow.value = query
    }

    fun selectSuggestion(suggestion: LocationSuggestion) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, query = suggestion.primaryText) }
            val result = getPlaceDetailsUseCase(suggestion.placeId)
            result.fold(
                onSuccess = { location ->
                    _uiState.update {
                        it.copy(
                            selectedLocation = location,
                            currentCoordinates = GeoPoint(location.latitude, location.longitude),
                            suggestions = emptyList(),
                            isSearching = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            errorMessage = err.message ?: "Unable to fetch place details"
                        )
                    }
                }
            )
        }
    }

    fun updateMapCoordinates(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentCoordinates = GeoPoint(latitude, longitude),
                    isReverseGeocoding = true
                )
            }
            val result = reverseGeocodeUseCase(latitude, longitude)
            result.fold(
                onSuccess = { location ->
                    _uiState.update {
                        it.copy(
                            selectedLocation = location,
                            query = location.formattedAddress,
                            isReverseGeocoding = false
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isReverseGeocoding = false) }
                }
            )
        }
    }

    fun useCurrentDeviceLocation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLocatingDevice = true, errorMessage = null) }
            val result = getCurrentDeviceLocationUseCase()
            result.fold(
                onSuccess = { geoPoint ->
                    if (geoPoint != null) {
                        updateMapCoordinates(geoPoint.latitude, geoPoint.longitude)
                    }
                    _uiState.update { it.copy(isLocatingDevice = false) }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            isLocatingDevice = false,
                            errorMessage = "Unable to get current location. Ensure location services are enabled."
                        )
                    }
                }
            )
        }
    }
}
