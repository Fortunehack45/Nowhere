package com.fakegps.mocklocation.engine

sealed class MockLocationError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Thrown when the app is not selected as the Mock Location App in Android Developer Options.
     */
    data class NotSelectedAsMockApp(
        override val message: String = "App is not set as the Mock Location Provider in Developer Options.",
        override val cause: Throwable? = null
    ) : MockLocationError(message, cause)

    /**
     * Thrown when location permissions are missing or revoked at runtime.
     */
    data class PermissionDenied(
        override val message: String = "Location permissions (ACCESS_FINE_LOCATION) have been denied.",
        override val cause: Throwable? = null
    ) : MockLocationError(message, cause)

    /**
     * Thrown when the system LocationManager fails to register or find a provider.
     */
    data class ProviderUnavailable(
        val providerName: String,
        override val message: String = "Location provider '$providerName' is unavailable.",
        override val cause: Throwable? = null
    ) : MockLocationError(message, cause)

    /**
     * Thrown for unexpected runtime errors during location spoofing.
     */
    data class InternalError(
        override val message: String,
        override val cause: Throwable? = null
    ) : MockLocationError(message, cause)
}
