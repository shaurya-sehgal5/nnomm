
package com.nnomm.config

import tools.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

@Component
class JwtAuthenticationFilter(
    @Value("\${JWT_SECRET:development-only-secret-key-change-before-deploying}")
    secret: String,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val signingKey = SecretKeySpec(
        secret.toByteArray(Charsets.UTF_8),
        "HmacSHA256"
    )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val token = header.removePrefix("Bearer ").trim()
            require(token.isNotBlank())

            // Check the declared algorithm before verifying the signature.
            val parts = token.split(".")
            require(parts.size == 3)

            val protectedHeader = objectMapper.readTree(
                Base64.getUrlDecoder().decode(parts[0])
            )
            require(protectedHeader.path("alg").asText() == "HS256")

            val claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .payload

            val userId = UUID.fromString(claims.subject)
            val role = claims["role"] as? String
                ?: throw IllegalArgumentException("Missing role")

            require(role == "customer" || role == "restaurant_admin")

            val restaurantId = if (role == "restaurant_admin") {
                UUID.fromString(
                    claims["restaurant_id"] as? String
                        ?: throw IllegalArgumentException("Missing restaurant_id")
                )
            } else {
                null
            }

            val user = AuthenticatedUser(userId, role, restaurantId)
            val authentication = UsernamePasswordAuthenticationToken(
                user,
                null,
                listOf(SimpleGrantedAuthority("ROLE_$role"))
            )

            SecurityContextHolder.getContext().authentication = authentication
            filterChain.doFilter(request, response)
        } catch (_: Exception) {
            SecurityContextHolder.clearContext()
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(
                """{"error":{"code":"UNAUTHENTICATED","message":"Missing or invalid authentication token"}}"""
            )
        }
    }
}
