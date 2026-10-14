
package com.nnomm.config

import io.jsonwebtoken.Jwts
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

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
        // Public GET endpoints: no JWT parsing required.
        val path = request.requestURI
        if (
            request.method == "GET" &&
            (
                path == "/actuator/health" ||
                path.startsWith("/actuator/health/") ||
                Regex("/v1/deals/[^/]+").matches(path)
            )
        ) {
            filterChain.doFilter(request, response)
            return
        }

        val header = request.getHeader("Authorization")

        if (header == null) {
            filterChain.doFilter(request, response)
            return
        }

        if (!header.startsWith("Bearer ")) {
            writeUnauthorized(response)
            return
        }

        val user = try {
            val token = header.removePrefix("Bearer ").trim()
            require(token.isNotBlank())

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

            AuthenticatedUser(userId, role, restaurantId)
        } catch (_: Exception) {
            writeUnauthorized(response)
            return
        }

        val authentication = UsernamePasswordAuthenticationToken(
            user,
            null,
            listOf(SimpleGrantedAuthority("ROLE_${user.role}"))
        )

        SecurityContextHolder.getContext().authentication = authentication
        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        SecurityContextHolder.clearContext()
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"error":{"code":"UNAUTHENTICATED","message":"Missing or invalid authentication token"}}"""
        )
    }
}
