
package com.nnomm.claim

import com.nnomm.config.AuthenticatedUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ClaimOperationResult(
    val status: Int,
    val body: String,
    val replayed: Boolean = false
)

@Service
class ClaimService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${CLAIM_TTL_SECONDS:600}") private val ttlSeconds: Long
) {
    @Transactional
    fun createClaim(
        user: AuthenticatedUser,
        dealId: UUID,
        quantity: Int,
        idempotencyKey: String?
    ): ClaimOperationResult {
        if (user.role != "customer") {
            return result(403, "FORBIDDEN", "Customer access required")
        }

        if (quantity !in 1..3) {
            return result(400, "VALIDATION_FAILED", "Quantity must be between 1 and 3")
        }

        if (
            idempotencyKey == null ||
            !Regex("^[A-Za-z0-9_-]{1,64}$").matches(idempotencyKey)
        ) {
            return result(
                400,
                "IDEMPOTENCY_KEY_INVALID",
                "A valid Idempotency-Key header is required"
            )
        }

        val now = Instant.now()
val dealExists = jdbc.queryForObject(
    "SELECT EXISTS (SELECT 1 FROM deals WHERE id = ?)",
    Boolean::class.java,
    dealId
) ?: false

if (!dealExists) {
    return result(404, "NOT_FOUND", "Deal not found")
}
        var keyInsert = jdbc.update(
            """
            INSERT INTO idempotency_records (
                customer_id, idempotency_key, deal_id, quantity, request_hash
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """.trimIndent(),
            user.userId,
            idempotencyKey,
            dealId,
            quantity,
            "$dealId:$quantity"
        )

        var existing = jdbc.query(
            """
            SELECT deal_id, quantity, response_status, response_body::text AS body,
                   completed_at, created_at
            FROM idempotency_records
            WHERE customer_id = ? AND idempotency_key = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                ExistingKey(
                    rs.getObject("deal_id", UUID::class.java),
                    rs.getInt("quantity"),
                    rs.getObject("response_status") as? Int,
                    rs.getString("body"),
                    rs.getTimestamp("completed_at")?.toInstant(),
                    rs.getTimestamp("created_at").toInstant()
                )
            },
            user.userId,
            idempotencyKey
        ).first()

       if (keyInsert == 0 &&
    existing.completedAt != null &&
    existing.createdAt.isBefore(now.minusSeconds(24 * 60 * 60))
) {
    jdbc.update(
        """
        DELETE FROM idempotency_records
        WHERE customer_id = ? AND idempotency_key = ?
        """.trimIndent(),
        user.userId,
        idempotencyKey
    )

    keyInsert = jdbc.update(
        """
        INSERT INTO idempotency_records (
            customer_id, idempotency_key, deal_id, quantity, request_hash
        ) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (customer_id, idempotency_key) DO NOTHING
        """.trimIndent(),
        user.userId,
        idempotencyKey,
        dealId,
        quantity,
        "$dealId:$quantity"
    )
} else if (existing.dealId != dealId || existing.quantity != quantity) {
    return result(
        422,
        "IDEMPOTENCY_KEY_REUSED",
        "This key was already used with different parameters"
    )
}

if (keyInsert == 0) {
    if (
        existing.completedAt != null &&
        existing.body != null &&
        existing.responseStatus != null
    ) {
        return ClaimOperationResult(
            existing.responseStatus,
            existing.body,
            replayed = true
        )
    }

    if (existing.createdAt.isBefore(now.minusSeconds(60))) {
        jdbc.update(
            """
            DELETE FROM idempotency_records
            WHERE customer_id = ?
              AND idempotency_key = ?
              AND completed_at IS NULL
            """.trimIndent(),
            user.userId,
            idempotencyKey
        )

        keyInsert = jdbc.update(
            """
            INSERT INTO idempotency_records (
                customer_id, idempotency_key, deal_id, quantity, request_hash
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """.trimIndent(),
            user.userId,
            idempotencyKey,
            dealId,
            quantity,
            "$dealId:$quantity"
        )

        if (keyInsert == 0) {
            return result(
                409,
                "REQUEST_IN_PROGRESS",
                "A request with this key is still processing"
            )
        }
    } else {
        return result(
            409,
            "REQUEST_IN_PROGRESS",
            "A request with this key is still processing"
        )
    }
}

        if (keyInsert == 0) {
            if (
                existing.completedAt != null &&
                existing.body != null &&
                existing.responseStatus != null
            ) {
                return ClaimOperationResult(
                    existing.responseStatus,
                    existing.body,
                    replayed = true
                )
            }

            if (existing.createdAt.isBefore(now.minusSeconds(60))) {
                jdbc.update(
                    """
                    DELETE FROM idempotency_records
                    WHERE customer_id = ?
                      AND idempotency_key = ?
                      AND completed_at IS NULL
                    """.trimIndent(),
                    user.userId,
                    idempotencyKey
                )

                keyInsert = jdbc.update(
                    """
                    INSERT INTO idempotency_records (
                        customer_id, idempotency_key, deal_id, quantity, request_hash
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                    """.trimIndent(),
                    user.userId,
                    idempotencyKey,
                    dealId,
                    quantity,
                    "$dealId:$quantity"
                )

                if (keyInsert == 0) {
                    return result(
                        409,
                        "REQUEST_IN_PROGRESS",
                        "A request with this key is still processing"
                    )
                }
            } else {
                return result(
                    409,
                    "REQUEST_IN_PROGRESS",
                    "A request with this key is still processing"
                )
            }
        }

        // Serialize stock allocation and active-claim checks across app instances.
        val deal = jdbc.query(
            """
            SELECT id, price_paise, stock_total, starts_at, ends_at
            FROM deals
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                DealRow(
                    rs.getInt("price_paise"),
                    rs.getInt("stock_total"),
                    rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at").toInstant()
                )
            },
            dealId
        ).firstOrNull()

        if (deal == null) {
            return finishKey(
                user.userId,
                idempotencyKey,
                result(404, "NOT_FOUND", "Deal not found")
            )
        }

        jdbc.update(
            """
            UPDATE claims
            SET status = 'EXPIRED'
            WHERE deal_id = ?
              AND status = 'RESERVED'
              AND expires_at <= NOW()
            """.trimIndent(),
            dealId
        )

        if (now < deal.startsAt || now >= deal.endsAt) {
            return finishKey(
                user.userId,
                idempotencyKey,
                result(409, "DEAL_NOT_LIVE", "This deal is not currently live")
            )
        }

        val alreadyClaimed = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM claims
                WHERE deal_id = ?
                  AND customer_id = ?
                  AND status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW')
            )
            """.trimIndent(),
            Boolean::class.java,
            dealId,
            user.userId
        ) ?: false

        if (alreadyClaimed) {
            return finishKey(
                user.userId,
                idempotencyKey,
                result(
                    409,
                    "ALREADY_CLAIMED",
                    "You already have an active claim for this deal"
                )
            )
        }

        val held = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(quantity), 0)
            FROM claims
            WHERE deal_id = ?
              AND (
                  status IN ('PAID', 'NEEDS_REVIEW')
                  OR (status = 'RESERVED' AND expires_at > NOW())
              )
            """.trimIndent(),
            Int::class.java,
            dealId
        ) ?: 0

        if (deal.stockTotal - held < quantity) {
            return finishKey(
                user.userId,
                idempotencyKey,
                result(409, "SOLD_OUT", "Not enough stock is available")
            )
        }

        val claimId = UUID.randomUUID()
        val expiresAt = now.plusSeconds(ttlSeconds.coerceAtLeast(1))
        val amount = Math.multiplyExact(deal.pricePaise, quantity)

        jdbc.update(
            """
            INSERT INTO claims (
                id, deal_id, customer_id, quantity, amount_paise,
                status, expires_at, created_at
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?)
            """.trimIndent(),
            claimId,
            dealId,
            user.userId,
            quantity,
            amount,
            Timestamp.from(expiresAt),
            Timestamp.from(now)
        )

        val response = ClaimResponse(
            id = claimId,
            deal_id = dealId,
            customer_id = user.userId,
            quantity = quantity,
            amount_paise = amount,
            status = "RESERVED",
            expires_at = expiresAt,
            created_at = now
        )

        val body = objectMapper.writeValueAsString(response)
        val output = ClaimOperationResult(201, body)

        jdbc.update(
            """
            UPDATE idempotency_records
            SET response_status = ?,
                response_body = ?::jsonb,
                claim_id = ?,
                completed_at = NOW()
            WHERE customer_id = ? AND idempotency_key = ?
            """.trimIndent(),
            output.status,
            output.body,
            claimId,
            user.userId,
            idempotencyKey
        )

        return output
    }

    @Transactional(readOnly = true)
    fun getClaim(claimId: UUID, user: AuthenticatedUser): ClaimResponse? {
        val claims = jdbc.query(
            """
            SELECT c.id, c.deal_id, c.customer_id, c.quantity, c.amount_paise,
                   CASE
                       WHEN c.status = 'RESERVED' AND c.expires_at <= NOW()
                       THEN 'EXPIRED'
                       ELSE c.status
                   END AS effective_status,
                   c.expires_at, c.created_at
            FROM claims c
            JOIN deals d ON d.id = c.deal_id
            WHERE c.id = ?
              AND (
                  c.customer_id = ?
                  OR (
                      ? = 'restaurant_admin'
                      AND d.restaurant_id = ?
                  )
              )
            """.trimIndent(),
            { rs, _ ->
                ClaimResponse(
                    id = rs.getObject("id", UUID::class.java),
                    deal_id = rs.getObject("deal_id", UUID::class.java),
                    customer_id = rs.getObject("customer_id", UUID::class.java),
                    quantity = rs.getInt("quantity"),
                    amount_paise = rs.getInt("amount_paise"),
                    status = rs.getString("effective_status"),
                    expires_at = rs.getTimestamp("expires_at").toInstant(),
                    created_at = rs.getTimestamp("created_at").toInstant()
                )
            },
            claimId,
            user.userId,
            user.role,
            user.restaurantId
        )

        return claims.firstOrNull()
    }

    private fun finishKey(
        customerId: UUID,
        key: String,
        output: ClaimOperationResult
    ): ClaimOperationResult {
        jdbc.update(
            """
            UPDATE idempotency_records
            SET response_status = ?,
                response_body = ?::jsonb,
                completed_at = NOW()
            WHERE customer_id = ? AND idempotency_key = ?
            """.trimIndent(),
            output.status,
            output.body,
            customerId,
            key
        )
        return output
    }

    private fun result(status: Int, code: String, message: String) =
        ClaimOperationResult(
            status,
            objectMapper.writeValueAsString(
                ClaimError(ClaimErrorDetails(code, message))
            )
        )

    private data class ExistingKey(
        val dealId: UUID,
        val quantity: Int,
        val responseStatus: Int?,
        val body: String?,
        val completedAt: Instant?,
        val createdAt: Instant
    )

    private data class DealRow(
        val pricePaise: Int,
        val stockTotal: Int,
        val startsAt: Instant,
        val endsAt: Instant
    )
}
