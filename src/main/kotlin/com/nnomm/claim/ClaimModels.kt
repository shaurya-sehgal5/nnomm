package com.nnomm.claim

import java.time.Instant
import java.util.UUID

data class CreateClaimRequest(
    val quantity: Int
)

data class ClaimResponse(
    val id: UUID,
    val deal_id: UUID,
    val customer_id: UUID,
    val quantity: Int,
    val amount_paise: Int,
    val status: String,
    val expires_at: Instant,
    val created_at: Instant
)

data class ClaimError(
    val error: ClaimErrorDetails
)

data class ClaimErrorDetails(
    val code: String,
    val message: String
)