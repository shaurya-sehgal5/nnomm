package com.nnomm.claim

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReservationExpiryService(
    private val jdbc: JdbcTemplate
) {
    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun expireReservations() {
        jdbc.update(
            """
            UPDATE claims
            SET status = 'EXPIRED'
            WHERE status = 'RESERVED'
              AND expires_at <= NOW()
            """.trimIndent()
        )
    }
}