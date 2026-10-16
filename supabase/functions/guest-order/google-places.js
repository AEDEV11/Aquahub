export class InvalidGooglePlaceError extends Error {}
export class GooglePlaceUnavailableError extends Error {
  constructor(message, { reason, status = null } = {}) {
    super(message);
    this.name = "GooglePlaceUnavailableError";
    this.reason = reason || "unknown";
    this.status = status;
  }
}

/** Resolve a browser-selected Place ID with the server-only Places API key. */
export async function resolveGooglePlace(placeId, apiKey, fetcher = fetch) {
  if (typeof placeId !== "string" || !placeId.trim() || placeId.length > 512) {
    throw new InvalidGooglePlaceError("The selected location is invalid.");
  }
  if (!apiKey) {
    throw new GooglePlaceUnavailableError("Location verification is not configured.", { reason: "missing_server_key" });
  }
  let response;
  try {
    response = await fetcher(`https://places.googleapis.com/v1/places/${encodeURIComponent(placeId.trim())}`, {
      headers: {
        "X-Goog-Api-Key": apiKey,
        "X-Goog-FieldMask": "id,formattedAddress,location",
      },
    });
  } catch {
    throw new GooglePlaceUnavailableError("The location service is unavailable.", { reason: "network_error" });
  }
  if (response.status === 404 || response.status === 400) {
    throw new InvalidGooglePlaceError("The selected location could not be confirmed.");
  }
  if (!response.ok) {
    throw new GooglePlaceUnavailableError("The location service is unavailable.", {
      reason: "provider_http_error",
      status: response.status,
    });
  }
  let place;
  try {
    place = await response.json();
  } catch {
    throw new GooglePlaceUnavailableError("The location service returned an invalid response.", {
      reason: "invalid_response",
      status: response.status,
    });
  }
  const latitude = Number(place?.location?.latitude);
  const longitude = Number(place?.location?.longitude);
  const address = typeof place?.formattedAddress === "string" ? place.formattedAddress.trim() : "";
  if (typeof place?.id !== "string" || place.id !== placeId.trim() || !address || address.length > 400
    || !Number.isFinite(latitude) || latitude < -90 || latitude > 90
    || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw new InvalidGooglePlaceError("The selected location does not have a valid delivery address.");
  }
  return { id: place.id, address, latitude, longitude };
}
