
package com.nnomm.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class PaymentWebhookService(
    private val jdbc: JdbcTemplate,
    @Value("\${WEBHOOK_SECRET:development-only-webhook-secret}")
    secret: String
) {
    private val signingKey = SecretKeySpec(
        secret.toByteArray(StandardCharsets.UTF_8),
        "HmacSHA256"
    )

    fun verifySignature(rawBody: ByteArray, header: String?): Boolean {
        if (header.isNullOrBlank() || rawBody.size > 16 * 1024) {
            return false
        }

        return try {
            val parts = header.split(",")
            if (parts.size != 2) return false

            val fields = mutableMapOf<String, String>()

            for (part in parts) {
                val pair = part.trim().split("=", limit = 2)
                if (pair.size != 2) return false

                val name = pair[0]
                val value = pair[1]

                if (name !in setOf("t", "v1")) return false
                if (fields.put(name, value) != null) return false
            }

            val timestampText = fields["t"] ?: return false
            val suppliedHex = fields["v1"] ?: return false

            if (!timestampText.matches(Regex("^[0-9]{1,12}$"))) {
                return false
            }
            if (!suppliedHex.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                return false
            }

            val timestamp = timestampText.toLong()
            val now = Instant.now().epochSecond
            if (kotlin.math.abs(now - timestamp) > 300) return false

            val message = timestampText.toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf('.'.code.toByte()) +
                rawBody

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(signingKey)
            val expected = mac.doFinal(message)
            val supplied = HexFormat.of().parseHex(suppliedHex)

            MessageDigest.isEqual(expected, supplied)
        } catch (_: Exception) {
            false
        }
    }

    @Transactional
    fun process(event: PaymentWebhookRequest): Int {
        if (
            event.event_id.isBlank() ||
            event.event_id.length > 200 ||
            event.event_type !in setOf("payment.succeeded", "payment.failed") ||
            event.amount_paise < 0
        ) {
            return 400
        }

        // Re-delivery of an already processed event must be harmless.
        val alreadyProcessed = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM payment_events WHERE event_id = ?)",
            Boolean::class.java,
            event.event_id
        ) ?: false

        if (alreadyProcessed) return 200

        val claim = jdbc.query(
            """
            SELECT id, status, amount_paise, expires_at
            FROM claims
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                ClaimPaymentRow(
                    rs.getString("status"),
                    rs.getInt("amount_paise"),
                    rs.getTimestamp("expires_at").toInstant()
                )
            },
            event.claim_id
        ).firstOrNull() ?: return 404

        val inserted = jdbc.update(
            """
            INSERT INTO payment_events (event_id, claim_id, event_type, amount_paise)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            event.event_id,
            event.claim_id,
            event.event_type,
            event.amount_paise
        )

        if (inserted == 0) return 200
        if (event.event_type == "payment.failed") {
    return 200
}


val priorFailure = jdbc.queryForObject(
    """
    SELECT EXISTS (
        SELECT 1
        FROM payment_events
        WHERE claim_id = ?
          AND event_type = 'payment.failed'
    )
    """.trimIndent(),
    Boolean::class.java,
    event.claim_id
) ?: false

if (priorFailure) return 200

        val now = Instant.now()

        val newStatus = when {
            claim.status == "EXPIRED" ||
                (claim.status == "RESERVED" && !claim.expiresAt.isAfter(now)) ->
                "REFUND_PENDING"

            claim.status == "RESERVED" &&
                event.amount_paise != claim.amountPaise ->
                "NEEDS_REVIEW"

            claim.status == "RESERVED" -> "PAID"

            else -> null
        }

        if (newStatus != null) {
            jdbc.update(
                "UPDATE claims SET status = ? WHERE id = ?",
                newStatus,
                event.claim_id
            )
        }

        return 200
    }

    private data class ClaimPaymentRow(
        val status: String,
        val amountPaise: Int,
        val expiresAt: Instant
    )
}
