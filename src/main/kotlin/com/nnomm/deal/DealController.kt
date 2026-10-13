package com.nnomm.deal

import com.nnomm.config.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/v1/deals")
class DealController(
    private val dealService: DealService
) {

    @PostMapping
    fun createDeal(
        @RequestBody request: CreateDealRequest
    ): ResponseEntity<Any> {
        val user = currentUser()

        if (user.role != "restaurant_admin" || user.restaurantId == null) {
            return error(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Restaurant admin access required"
            )
        }

        return try {
            ResponseEntity.status(HttpStatus.CREATED)
                .body(dealService.createDeal(user.restaurantId, request))
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Invalid request"
            val status = if (message == "Restaurant not found") {
                HttpStatus.NOT_FOUND
            } else {
                HttpStatus.BAD_REQUEST
            }
            error(status, if (status == HttpStatus.NOT_FOUND) "NOT_FOUND" else "VALIDATION_FAILED", message)
        }
    }

    @GetMapping("/{dealId}")
    fun getDeal(@PathVariable dealId: UUID): ResponseEntity<Any> {
        val deal = dealService.getDeal(dealId)
            ?: return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Deal not found")

        return ResponseEntity.ok(deal)
    }

    private fun currentUser(): AuthenticatedUser {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw IllegalStateException("Authentication required")

        return authentication.principal as? AuthenticatedUser
            ?: throw IllegalStateException("Invalid authenticated user")
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String
    ): ResponseEntity<Any> {
        return ResponseEntity.status(status)
            .body(ApiError(ErrorDetails(code, message)))
    }
}