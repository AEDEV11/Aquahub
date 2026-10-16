package com.aesprt.aquahub.core.common

sealed class AppError(message: String? = null) : Exception(message) {
    class ValidationError(message: String) : AppError(message)
    class NotFoundError(entity: String, id: Long? = null) : AppError(
        if (id != null) "$entity with id $id not found" else "$entity not found"
    )

    class InvalidOrderTransitionError(from: String, to: String) : AppError(
        "Cannot transition order from '$from' to '$to'"
    )

    class RiderUnavailableError : AppError("No available riders at this time")
    class InsufficientInventoryError(size: String) : AppError(
        "Insufficient $size container inventory"
    )

    class AuthenticationError(message: String = "Invalid phone number or password") :
        AppError(message)

    class NetworkError(message: String = "Network request failed. Please try again.") :
        AppError(message)

    class UnknownError(cause: Throwable? = null) :
        AppError(cause?.message ?: "An unknown error occurred")
}
