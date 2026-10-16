let mapsApiPromise;
let mapsCallbackSequence = 0;
let mapsAuthFailureHookInstalled = false;

function installMapsAuthFailureHook() {
  if (mapsAuthFailureHookInstalled) return;
  const previousHandler = window.gm_authFailure;
  window.gm_authFailure = () => {
    window.dispatchEvent(new Event("aquahub:maps-auth-failure"));
    if (typeof previousHandler === "function") previousHandler();
  };
  mapsAuthFailureHookInstalled = true;
}

function loadGoogleMaps(apiKey) {
  installMapsAuthFailureHook();
  if (window.google?.maps?.importLibrary) return Promise.resolve(window.google.maps);
  if (mapsApiPromise) return mapsApiPromise;
  mapsApiPromise = new Promise((resolve, reject) => {
    const callbackName = `__aquahubMapsReady${++mapsCallbackSequence}`;
    const script = document.createElement("script");
    let settled = false;
    const timeoutId = window.setTimeout(() => finish(new Error("Address search took too long to load.")), 15_000);
    const finish = (error) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeoutId);
      delete window[callbackName];
      if (error) {
        script.remove();
        reject(error);
      } else if (window.google?.maps?.importLibrary) {
        resolve(window.google.maps);
      } else {
        reject(new Error("Address search could not be loaded."));
      }
    };
    const query = new URLSearchParams({
      key: apiKey,
      v: "weekly",
      loading: "async",
      libraries: "places",
      callback: callbackName,
    });
    window[callbackName] = () => finish();
    script.src = `https://maps.googleapis.com/maps/api/js?${query}`;
    script.async = true;
    script.onerror = () => finish(new Error("Address search could not be loaded."));
    document.head.append(script);
  }).catch((error) => {
    mapsApiPromise = null;
    throw error;
  });
  return mapsApiPromise;
}

function googleMapsFailureCopy(error) {
  const detail = `${error?.name || ""} ${error?.message || ""}`;
  if (/RefererNotAllowedMapError/i.test(detail)) {
    return "This website is not on the Google Maps key’s allowed domains. The station needs to update its map setup.";
  }
  if (/ApiNotActivatedMapError|Places.*not enabled/i.test(detail)) {
    return "Google Maps or Places is not enabled for this website yet. The station needs to check its map setup.";
  }
  if (/BillingNotEnabledMapError/i.test(detail)) {
    return "Google Maps is unavailable because billing is not enabled for the station’s map project.";
  }
  if (/InvalidKeyMapError/i.test(detail)) {
    return "The Google Maps key for this website is invalid. The station needs to check its map setup.";
  }
  if (/OverQuotaMapError|OVER_QUERY_LIMIT|QuotaExceeded/i.test(detail)) {
    return "Google Maps has reached its request limit. Please try again later or choose pickup.";
  }
  return "Map search could not start. Check your connection or choose pickup, then try again later.";
}

function pointNumbers(point) {
  return {
    latitude: typeof point.lat === "function" ? point.lat() : Number(point.lat),
    longitude: typeof point.lng === "function" ? point.lng() : Number(point.lng),
  };
}

function validPoint({ latitude, longitude }) {
  return Number.isFinite(latitude) && latitude >= -90 && latitude <= 90
    && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
}

export async function openGoogleLocationPicker({ apiKey, station, initialLocation, onConfirm }) {
  if (!apiKey) throw new Error("Map search is not configured yet. Please choose pickup or contact the station.");
  if (!window.HTMLDialogElement || typeof document.createElement("dialog").showModal !== "function") {
    throw new Error("This browser does not support the secure location picker. Please update your browser or choose pickup.");
  }

  const dialog = document.createElement("dialog");
  dialog.className = "location-dialog";
  dialog.setAttribute("aria-labelledby", "location-dialog-title");
  dialog.innerHTML = `<div class="location-dialog-panel"><header class="location-dialog-header"><div><p class="eyebrow">DELIVERY DESTINATION</p><h2 id="location-dialog-title">Find your address</h2><p>Search for your address or move the pin to the delivery point.</p></div><button class="icon-button" type="button" data-map-action="close" aria-label="Close location picker"><span class="app-icon" aria-hidden="true">close</span></button></header><div class="location-search" data-location-search></div><div class="location-map" data-location-map role="region" aria-label="Map. Search for an address or choose a point on the map."></div><p class="location-map-status" data-location-status role="status" aria-live="polite">Search for an address or tap the map to choose your delivery point.</p><div class="location-dialog-actions"><button class="button secondary" type="button" data-map-action="use-current">Use my current location</button><span class="map-attribution">Map data © Google</span><button class="button secondary" type="button" data-map-action="cancel">Cancel</button><button class="button primary" type="button" data-map-action="confirm" disabled>Use this pin</button></div></div>`;
  document.body.append(dialog);
  dialog.showModal();

  const status = dialog.querySelector("[data-location-status]");
  const confirmButton = dialog.querySelector("[data-map-action='confirm']");
  const currentButton = dialog.querySelector("[data-map-action='use-current']");
  let candidate = null;
  let map = null;
  let marker = null;
  let geocoder = null;
  let active = true;
  let lookupSequence = 0;
  const listeners = [];
  let autocomplete = null;
  let autocompleteSelection = null;

  const setStatus = (message, kind = "info") => {
    if (!status || !active) return;
    status.textContent = message;
    status.dataset.kind = kind;
  };
  const close = () => {
    if (dialog.open) dialog.close();
    else cleanup();
  };
  const cleanup = () => {
    active = false;
    lookupSequence++;
    listeners.forEach((listener) => listener.remove());
    window.removeEventListener("aquahub:maps-auth-failure", onMapsAuthFailure);
    if (autocomplete && autocompleteSelection) autocomplete.removeEventListener("gmp-select", autocompleteSelection);
    dialog.remove();
  };
  const onMapsAuthFailure = () => setStatus(
    "Google Maps couldn’t authorize this website. Check the browser key’s allowed domains, enabled APIs, and billing.",
    "error",
  );
  window.addEventListener("aquahub:maps-auth-failure", onMapsAuthFailure);
  dialog.addEventListener("close", cleanup, { once: true });
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) close();
    const button = event.target.closest("[data-map-action]");
    if (!button) return;
    if (button.dataset.mapAction === "close" || button.dataset.mapAction === "cancel") close();
    if (button.dataset.mapAction === "confirm" && candidate) {
      onConfirm(candidate);
      close();
    }
    if (button.dataset.mapAction === "use-current") useCurrentLocation();
  });

  const selectPlace = (placeId, address, point) => {
    const coordinates = pointNumbers(point);
    if (!placeId || !address || !validPoint(coordinates)) {
      setStatus("Choose a result with a verified street or place address.", "error");
      return;
    }
    candidate = { placeId, address, ...coordinates };
    marker.setPosition({ lat: coordinates.latitude, lng: coordinates.longitude });
    marker.setVisible(true);
    map.panTo({ lat: coordinates.latitude, lng: coordinates.longitude });
    map.setZoom(Math.max(map.getZoom() || 15, 16));
    confirmButton.disabled = false;
    setStatus(`Delivery pin selected: ${address}`);
  };

  const resolveMapPoint = async (point) => {
    const sequence = ++lookupSequence;
    setStatus("Finding the nearest address…");
    confirmButton.disabled = true;
    try {
      const response = await geocoder.geocode({ location: point });
      if (!active || sequence !== lookupSequence) return;
      const result = response.results?.find((entry) => entry.place_id && entry.formatted_address && entry.geometry?.location);
      if (!result) {
        setStatus("No street or place address was found here. Search for a nearby address, then move the pin.", "error");
        return;
      }
      selectPlace(result.place_id, result.formatted_address, result.geometry.location);
    } catch {
      if (active && sequence === lookupSequence) setStatus("We couldn’t confirm that map point. Search for an address and try again.", "error");
    }
  };

  const useCurrentLocation = () => {
    if (!navigator.geolocation) {
      setStatus("This browser does not provide device location. Search for your address or tap the map instead.", "error");
      return;
    }
    currentButton.disabled = true;
    setStatus("Finding your current location…");
    navigator.geolocation.getCurrentPosition((position) => {
      currentButton.disabled = false;
      resolveMapPoint({ lat: position.coords.latitude, lng: position.coords.longitude });
    }, (error) => {
      currentButton.disabled = false;
      const message = error.code === error.PERMISSION_DENIED
        ? "Location permission is off. Search for your address or tap the map to place the pin."
        : "We couldn’t get your current location. Search for your address or tap the map instead.";
      setStatus(message, "error");
    }, { enableHighAccuracy: true, timeout: 12_000, maximumAge: 60_000 });
  };

  try {
    const maps = await loadGoogleMaps(apiKey);
    const [{ Map }, { PlaceAutocompleteElement }, { Geocoder }] = await Promise.all([
      maps.importLibrary("maps"),
      maps.importLibrary("places"),
      maps.importLibrary("geocoding"),
    ]);
    if (!active) return;
    geocoder = new Geocoder();
    const hasStationPoint = validPoint({ latitude: station.latitude, longitude: station.longitude });
    const stationPoint = hasStationPoint
      ? { lat: station.latitude, lng: station.longitude }
      : { lat: 12.8797, lng: 121.7740 };
    const hasInitial = initialLocation?.placeId && validPoint(initialLocation);
    const center = hasInitial ? { lat: initialLocation.latitude, lng: initialLocation.longitude } : stationPoint;
    map = new Map(dialog.querySelector("[data-location-map]"), {
      center,
      zoom: hasInitial || hasStationPoint ? 15 : 6,
      mapTypeControl: false,
      streetViewControl: false,
      fullscreenControl: false,
      clickableIcons: false,
      gestureHandling: "cooperative",
    });
    marker = new maps.Marker({ map, position: center, draggable: true, visible: Boolean(hasInitial), title: "Delivery location pin" });
    listeners.push(map.addListener("click", (event) => { if (event.latLng) resolveMapPoint(event.latLng); }));
    listeners.push(marker.addListener("dragend", (event) => { if (event.latLng) resolveMapPoint(event.latLng); }));

    autocomplete = new PlaceAutocompleteElement();
    autocomplete.includedRegionCodes = ["ph"];
    dialog.querySelector("[data-location-search]").append(autocomplete);
    autocompleteSelection = async (event) => {
      const sequence = ++lookupSequence;
      confirmButton.disabled = true;
      setStatus("Confirming the selected address…");
      try {
        const place = event.placePrediction?.toPlace();
        if (!place) throw new Error("Address selection is unavailable.");
        await place.fetchFields({ fields: ["id", "formattedAddress", "location"] });
        if (!active || sequence !== lookupSequence) return;
        selectPlace(place.id, place.formattedAddress, place.location);
      } catch {
        if (active && sequence === lookupSequence) setStatus("We couldn’t confirm that address. Choose another result.", "error");
      }
    };
    autocomplete.addEventListener("gmp-select", autocompleteSelection);
    if (hasInitial) selectPlace(initialLocation.placeId, initialLocation.address, center);
  } catch (error) {
    setStatus(googleMapsFailureCopy(error), "error");
  }
}
