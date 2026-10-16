package com.aesprt.aquahub.domain.model

import java.util.UUID

object CustomerOrderingLink {
    private const val BASE_URL = "https://aquahub.aesprt.com/s/"

    fun newPublicStationCode(): String =
        "AQ" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase()

    fun forStation(code: String, qr: Boolean = false): String =
        BASE_URL + code + if (qr) "?source=qr" else ""
}
