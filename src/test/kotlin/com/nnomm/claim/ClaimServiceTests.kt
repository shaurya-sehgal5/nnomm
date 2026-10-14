
package com.nnomm.claim

import com.nnomm.config.AuthenticatedUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class ClaimServiceTests {

    @Autowired
    lateinit var claimService: ClaimService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `claim requires a valid idempotency key`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "customer", null)

        val result = claimService.createClaim(
            user = user,
            dealId = UUID.randomUUID(),
            quantity = 1,
            idempotencyKey = null
        )

        assertEquals(400, result.status)
        assertTrue(result.body.contains("IDEMPOTENCY_KEY_INVALID"))
    }

@Test
fun `customer cannot read another customers claim`() {
    val (claimId, _) = createClaimFixture()

    val otherCustomer = AuthenticatedUser(
        UUID.randomUUID(),
        "customer",
        null
    )

    assertEquals(null, claimService.getClaim(claimId, otherCustomer))
}

@Test
fun `restaurant admin cannot read another restaurants claim`() {
    val (claimId, _) = createClaimFixture()

    val otherRestaurantAdmin = AuthenticatedUser(
        UUID.randomUUID(),
        "restaurant_admin",
        UUID.randomUUID()
    )

    assertEquals(
        null,
        claimService.getClaim(claimId, otherRestaurantAdmin)
    )
}

private fun createClaimFixture(): Pair<UUID, UUID> {
    val restaurantId = UUID.randomUUID()
    val dealId = UUID.randomUUID()
    val claimId = UUID.randomUUID()
    val customerId = UUID.randomUUID()

    jdbc.update(
        "INSERT INTO restaurants (id, name) VALUES (?, ?)",
        restaurantId,
        "Authorization Test Restaurant"
    )

    jdbc.update(
        """
        INSERT INTO deals (
            id, restaurant_id, title, price_paise, stock_total,
            starts_at, ends_at
        ) VALUES (
            ?, ?, ?, ?, ?, NOW() - INTERVAL '1 hour',
            NOW() + INTERVAL '1 hour'
        )
        """.trimIndent(),
        dealId,
        restaurantId,
        "Authorization Test Deal",
        5000,
        10
    )

    jdbc.update(
        """
        INSERT INTO claims (
            id, deal_id, customer_id, quantity, amount_paise,
            status, expires_at, created_at
        ) VALUES (
            ?, ?, ?, 1, 5000, 'RESERVED',
            NOW() + INTERVAL '10 minutes', NOW()
        )
        """.trimIndent(),
        claimId,
        dealId,
        customerId
    )

    return claimId to restaurantId
}

    @Test
    fun `claim quantity must be between one and three`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "customer", null)

        val result = claimService.createClaim(
            user = user,
            dealId = UUID.randomUUID(),
            quantity = 4,
            idempotencyKey = "quantity_test"
        )

        assertEquals(400, result.status)
        assertTrue(result.body.contains("VALIDATION_FAILED"))
    }

    @Test
    fun `restaurant admin cannot create customer claim`() {
        val user = AuthenticatedUser(
            UUID.randomUUID(),
            "restaurant_admin",
            UUID.randomUUID()
        )

        val result = claimService.createClaim(
            user = user,
            dealId = UUID.randomUUID(),
            quantity = 1,
            idempotencyKey = "admin_test"
        )

        assertEquals(403, result.status)
        assertTrue(result.body.contains("FORBIDDEN"))
    }

    @Test
    fun `missing claim is not returned to a customer`() {
        val user = AuthenticatedUser(UUID.randomUUID(), "customer", null)

        val claim = claimService.getClaim(UUID.randomUUID(), user)

        assertFalse(claim != null)
    }
}
