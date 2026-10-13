package com.nnomm.claim

import com.nnomm.config.AuthenticatedUser
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@RestController
class ClaimController(
    private val claimService: ClaimService,
    private val objectMapper: ObjectMapper
) {

    @PostMapping("/v1/deals/{dealId}/claims")
    fun createClaim(
        @PathVariable dealId: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: CreateClaimRequest
    ): ResponseEntity<Any> {
        val user = currentUser()
        val output = claimService.createClaim(
            user, dealId, request.quantity, idempotencyKey
        )

        val headers = HttpHeaders()
        if (output.replayed) {
            headers.add("Idempotent-Replayed", "true")
        }

        return ResponseEntity.status(output.status)
            .headers(headers)
            .body(objectMapper.readTree(output.body))
    }

    @GetMapping("/v1/claims/{claimId}")
    fun getClaim(@PathVariable claimId: UUID): ResponseEntity<Any> {
        val claim = claimService.getClaim(claimId, currentUser())
            ?: return ResponseEntity.status(404).body(
                ClaimError(ClaimErrorDetails("NOT_FOUND", "Claim not found"))
            )

        return ResponseEntity.ok(claim)
    }

    private fun currentUser(): AuthenticatedUser {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw IllegalStateException("Authentication required")

        return authentication.principal as? AuthenticatedUser
            ?: throw IllegalStateException("Invalid authenticated user")
    }
}