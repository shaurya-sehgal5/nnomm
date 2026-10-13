package com.nnomm.deal

import java.time.Instant
import java.util.UUID

data class CreateDealRequest(
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val starts_at: Instant,
    val ends_at: Instant
)

data class DealResponse(
    val id: UUID,
    val restaurant_id: UUID,
    val title: String,
    val price_paise: Int,
    val stock_total: Int,
    val stock_available: Int,
    val starts_at: Instant,
    val ends_at: Instant
)

data class ApiError(
    val error: ErrorDetails
)

data class ErrorDetails(
    val code: String,
    val message: String
)