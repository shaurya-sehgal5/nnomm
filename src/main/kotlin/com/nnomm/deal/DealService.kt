package com.nnomm.deal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class DealService(
    private val jdbc: JdbcTemplate
) {

    @Transactional
    fun createDeal(
        restaurantId: UUID,
        request: CreateDealRequest
    ): DealResponse {
        val title = request.title.trim()

        require(title.isNotEmpty() && title.length <= 100) {
            "Title must contain 1–100 characters"
        }
        require(request.price_paise in 1..1_000_000) {
            "Price must be between 1 and 1000000 paise"
        }
        require(request.stock_total in 1..10_000) {
            "Stock must be between 1 and 10000"
        }
        require(request.ends_at > request.starts_at) {
            "ends_at must be after starts_at"
        }

        val dealId = UUID.randomUUID()

        // The restaurant must already exist. Never create a fake
        // restaurant automatically when an admin creates a deal.
        val restaurantExists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM restaurants WHERE id = ?)",
            Boolean::class.java,
            restaurantId
        ) ?: false

        require(restaurantExists) {
            "Restaurant not found"
        }

        jdbc.update(
            """
            INSERT INTO deals (
                id, restaurant_id, title, price_paise,
                stock_total, starts_at, ends_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            dealId,
            restaurantId,
            title,
            request.price_paise,
            request.stock_total,
            Timestamp.from(request.starts_at),
            Timestamp.from(request.ends_at)
        )

        return DealResponse(
            id = dealId,
            restaurant_id = restaurantId,
            title = title,
            price_paise = request.price_paise,
            stock_total = request.stock_total,
            stock_available = request.stock_total,
            starts_at = request.starts_at,
            ends_at = request.ends_at
        )
    }

    fun getDeal(dealId: UUID): DealResponse? {
        val deals = jdbc.query(
            """
            SELECT
                d.id,
                d.restaurant_id,
                d.title,
                d.price_paise,
                d.stock_total,
                d.starts_at,
                d.ends_at,
                d.stock_total - COALESCE(
                    SUM(c.quantity) FILTER (
                        WHERE c.status IN ('PAID', 'NEEDS_REVIEW')
                           OR (
                               c.status = 'RESERVED'
                               AND c.expires_at > NOW()
                           )
                    ),
                    0
                )::INTEGER AS stock_available
            FROM deals d
            LEFT JOIN claims c ON c.deal_id = d.id
            WHERE d.id = ?
            GROUP BY d.id
            """.trimIndent(),
            { rs, _ ->
                DealResponse(
                    id = rs.getObject("id", UUID::class.java),
                    restaurant_id = rs.getObject("restaurant_id", UUID::class.java),
                    title = rs.getString("title"),
                    price_paise = rs.getInt("price_paise"),
                    stock_total = rs.getInt("stock_total"),
                    stock_available = rs.getInt("stock_available"),
                    starts_at = rs.getTimestamp("starts_at").toInstant(),
                    ends_at = rs.getTimestamp("ends_at").toInstant()
                )
            },
            dealId
        )

        return deals.firstOrNull()
    }
}