package com.nnomm.payment

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v1/webhooks/payments")
class PaymentWebhookController(
    private val service: PaymentWebhookService,
    private val objectMapper: ObjectMapper
) {
    @PostMapping
    fun receive(
        @RequestHeader("X-Payment-Signature", required = false) signature: String?,
        @RequestBody rawBody: ByteArray
    ): ResponseEntity<Any> {
        if (rawBody.size > 16 * 1024) {
            return ResponseEntity.status(413).body(
                mapOf("error" to mapOf(
                    "code" to "PAYLOAD_TOO_LARGE",
                    "message" to "Request body exceeds 16 KB"
                ))
            )
        }

        if (!service.verifySignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf("error" to mapOf(
                    "code" to "INVALID_SIGNATURE",
                    "message" to "Invalid or expired webhook signature"
                ))
            )
        }

        val event = try {
            objectMapper.readValue(rawBody, PaymentWebhookRequest::class.java)
        } catch (_: Exception) {
            return ResponseEntity.badRequest().body(
                mapOf("error" to mapOf(
                    "code" to "VALIDATION_FAILED",
                    "message" to "Invalid payment event body"
                ))
            )
        }

        return when (val status = service.process(event)) {
            200 -> ResponseEntity.ok(PaymentWebhookResponse(received = true))
            400 -> ResponseEntity.badRequest().body(
                mapOf("error" to mapOf(
                    "code" to "INVALID_EVENT",
                    "message" to "Invalid payment event"
                ))
            )
            404 -> ResponseEntity.status(404).body(
                mapOf("error" to mapOf(
                    "code" to "NOT_FOUND",
                    "message" to "Claim not found"
                ))
            )
            else -> ResponseEntity.status(status).body(
                mapOf("error" to mapOf(
                    "code" to "WEBHOOK_ERROR",
                    "message" to "Unable to process payment event"
                ))
            )
        }
    }
}