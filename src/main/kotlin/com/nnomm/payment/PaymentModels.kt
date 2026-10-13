package com.nnomm.payment

import java.util.UUID

data class PaymentWebhookRequest(
    val event_id: String,
    val event_type: String,
    val claim_id: UUID,
    val amount_paise: Int
)

data class PaymentWebhookResponse(
    val received: Boolean
)

data class PaymentError(
    val error: PaymentErrorDetails
)

data class PaymentErrorDetails(
    val code: String,
    val message: String
)