
package com.nnomm.claim

import com.nnomm.config.AuthenticatedUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
class ClaimConcurrencyTests {

    @Autowired
    lateinit var claimService: ClaimService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var restaurantId: UUID
    private lateinit var dealId: UUID

    @BeforeEach
    fun prepareDatabase() {
        restaurantId = UUID.randomUUID()
        dealId = UUID.randomUUID()

        jdbc.update(
            "INSERT INTO restaurants (id, name) VALUES (?, ?)",
            restaurantId,
            "Concurrency Test $restaurantId"
        )

        jdbc.update(
            """
            INSERT INTO deals (
                id, restaurant_id, title, price_paise, stock_total,
                starts_at, ends_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            dealId,
            restaurantId,
            "Concurrency Test Deal",
            1000,
            50,
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
            java.sql.Timestamp.from(Instant.now().plusSeconds(3600))
        )
    }

    @Test
    fun `200 customers cannot claim more than 50 units`() {
        val workers = 200
        val ready = CountDownLatch(32)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(32)

        try {
            val futures = (1..workers).map {
                pool.submit(Callable {
                    val user = AuthenticatedUser(
                        UUID.randomUUID(),
                        "customer",
                        null
                    )
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS))

                    claimService.createClaim(
                        user,
                        dealId,
                        1,
                        "stock_test_$it"
                    )
                })
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map {
                it.get(90, TimeUnit.SECONDS)
            }

            val successful = results.count { it.status == 201 }
            assertEquals(50, successful)

            val held = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(quantity), 0)
                FROM claims
                WHERE deal_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
                  AND (status <> 'RESERVED' OR expires_at > NOW())
                """.trimIndent(),
                Long::class.java,
                dealId
            ) ?: 0L

            assertEquals(50L, held)
            assertTrue(held <= 50L)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `20 concurrent requests with same key create one claim`() {
        val customerId = UUID.randomUUID()
        val workers = 20
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)

        try {
            val futures = (1..workers).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS))

                    claimService.createClaim(
                        AuthenticatedUser(customerId, "customer", null),
                        dealId,
                        1,
                        "same_concurrent_key"
                    )
                })
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map {
                it.get(90, TimeUnit.SECONDS)
            }

            assertTrue(results.any { it.status == 201 })

            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claims WHERE deal_id = ? AND customer_id = ?",
                Long::class.java,
                dealId,
                customerId
            ) ?: 0L

            assertEquals(1L, count)
            assertTrue(results.all { it.status == 201 || it.status == 409 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same customer cannot create multiple active claims using different keys`() {
        val customerId = UUID.randomUUID()
        val workers = 10
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)

        try {
            val futures = (1..workers).map { index ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS))

                    claimService.createClaim(
                        AuthenticatedUser(customerId, "customer", null),
                        dealId,
                        1,
                        "different_key_$index"
                    )
                })
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()

            futures.forEach {
                it.get(90, TimeUnit.SECONDS)
            }

            val count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM claims
                WHERE deal_id = ?
                  AND customer_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
                """.trimIndent(),
                Long::class.java,
                dealId,
                customerId
            ) ?: 0L

            assertEquals(1L, count)
        } finally {
            pool.shutdownNow()
        }
    }
}
