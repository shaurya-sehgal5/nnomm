
package com.nnomm.payment
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class PaymentWebhookServiceTests {
@Autowired
lateinit var jdbc: JdbcTemplate
    @Autowired
    lateinit var service: PaymentWebhookService

    private fun signature(body: ByteArray, timestamp: Long): String {
        val secret = System.getenv("WEBHOOK_SECRET")
            ?: "development-only-webhook-secret"

        val message = "$timestamp.".toByteArray(StandardCharsets.UTF_8) + body
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secret.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
        )

        val hex = mac.doFinal(message).joinToString("") {
            "%02x".format(it)
        }

        return "t=$timestamp,v1=$hex"
    }
@Test
fun `success after recorded failure does not mark claim paid`() {
    val claimId = createReservedClaim()

    val failed = PaymentWebhookRequest(
        event_id = "evt-failed-${UUID.randomUUID()}",
        event_type = "payment.failed",
        claim_id = claimId,
        amount_paise = 5000
    )

    val succeeded = PaymentWebhookRequest(
        event_id = "evt-success-${UUID.randomUUID()}",
        event_type = "payment.succeeded",
        claim_id = claimId,
        amount_paise = 5000
    )

    assertEquals(200, service.process(failed))
    assertEquals(200, service.process(succeeded))

    val status = jdbc.queryForObject(
        "SELECT status FROM claims WHERE id = ?",
        String::class.java,
        claimId
    )

    assertEquals("RESERVED", status)
}
    @Test
    fun `rejects missing signature`() {
        assertFalse(
            service.verifySignature(
                """{"event_id":"evt-test"}""".toByteArray(),
                null
            )
        )
    }

@Test
fun `successful payment with wrong amount needs review`() {
    val claimId = createReservedClaim(amountPaise = 5000)

    val event = PaymentWebhookRequest(
        event_id = "evt-wrong-amount-${UUID.randomUUID()}",
        event_type = "payment.succeeded",
        claim_id = claimId,
        amount_paise = 4000
    )

    assertEquals(200, service.process(event))

    val status = jdbc.queryForObject(
        "SELECT status FROM claims WHERE id = ?",
        String::class.java,
        claimId
    )

    assertEquals("NEEDS_REVIEW", status)
}

@Test
fun `successful payment after expiry requires refund`() {
    val claimId = createReservedClaim(expiresInSeconds = -60)

    val event = PaymentWebhookRequest(
        event_id = "evt-expired-${UUID.randomUUID()}",
        event_type = "payment.succeeded",
        claim_id = claimId,
        amount_paise = 5000
    )

    assertEquals(200, service.process(event))

    val status = jdbc.queryForObject(
        "SELECT status FROM claims WHERE id = ?",
        String::class.java,
        claimId
    )

    assertEquals("REFUND_PENDING", status)
}

    @Test
    fun `duplicate successful payment event is harmless`() {
        val claimId = createReservedClaim()
        val event = PaymentWebhookRequest(
            event_id = "evt-duplicate-${java.util.UUID.randomUUID()}",
            event_type = "payment.succeeded",
            claim_id = claimId,
            amount_paise = 5000
        )

        assertEquals(200, service.process(event))
        assertEquals(200, service.process(event))

        val status = jdbc.queryForObject(
            "SELECT status FROM claims WHERE id = ?",
            String::class.java,
            claimId
        )

        assertEquals("PAID", status)

        val eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_events WHERE event_id = ?",
            Int::class.java,
            event.event_id
        )

        assertEquals(1, eventCount)
    }

    @Test
    fun `rejects invalid signature`() {
        val body = """{"event_id":"evt-test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond

        assertFalse(
            service.verifySignature(
                body,
                "t=$timestamp,v1=${"0".repeat(64)}"
            )
        )
    }

    @Test
    fun `rejects stale timestamp`() {
        val body = """{"event_id":"evt-test"}""".toByteArray()
        val staleTimestamp = Instant.now().epochSecond - 600

        assertFalse(
            service.verifySignature(
                body,
                signature(body, staleTimestamp)
            )
        )
    }

    @Test
    fun `accepts valid signature`() {
        val body = """{"event_id":"evt-test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond

        assertTrue(
            service.verifySignature(
                body,
                signature(body, timestamp)
            )
        )
    }
   
    private fun createReservedClaim(
        quantity: Int = 1,
        amountPaise: Int = 5000,
        expiresInSeconds: Long = 600
    ): java.util.UUID {
        val restaurantId = java.util.UUID.randomUUID()
        val dealId = java.util.UUID.randomUUID()
        val claimId = java.util.UUID.randomUUID()
        val customerId = java.util.UUID.randomUUID()

        jdbc.update(
            "INSERT INTO restaurants (id, name) VALUES (?, ?)",
            restaurantId, "Webhook Test Restaurant"
        )
        jdbc.update(
            """
            INSERT INTO deals (
                id, restaurant_id, title, price_paise, stock_total,
                starts_at, ends_at
            ) VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '1 hour',
                      NOW() + INTERVAL '1 hour')
            """.trimIndent(),
            dealId, restaurantId, "Webhook Test Deal", amountPaise, 10
        )
        jdbc.update(
            """
            INSERT INTO claims (
                id, deal_id, customer_id, quantity, amount_paise,
                status, expires_at, created_at
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED',
                      NOW() + (? * INTERVAL '1 second'), NOW())
            """.trimIndent(),
            claimId, dealId, customerId, quantity, amountPaise,
            expiresInSeconds
        )

        return claimId
    }

}