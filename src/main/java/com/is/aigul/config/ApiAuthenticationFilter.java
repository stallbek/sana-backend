package com.is.aigul.config;

import com.is.aigul.service.AuthRateLimitService;
import com.is.aigul.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protects new API routes automatically; credentials are accepted only in the token header. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> AUTH_ROUTES = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/logout");
    private final AuthService authService;
    private final AuthRateLimitService rateLimits;

    public ApiAuthenticationFilter(AuthService authService, AuthRateLimitService rateLimits) {
        this.authService = authService;
        this.rateLimits = rateLimits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean authRoute = "POST".equals(request.getMethod()) && AUTH_ROUTES.contains(path);
        if (authRoute && !path.endsWith("/logout")) {
            long retryAfter = rateLimits.retryAfter(request.getRemoteAddr(), path.endsWith("/register"));
            if (retryAfter > 0) {
                response.setHeader("Retry-After", Long.toString(retryAfter));
                error(response, 429, "AUTH_RATE_LIMITED", "Too many authentication attempts");
                return;
            }
        }
        boolean publicRoute = authRoute || "GET".equals(request.getMethod()) && "/".equals(path);
        String token = request.getHeader("token");
        Long userId = publicRoute || token == null || token.isBlank() ? null : authService.getUserId(token);
        if (!publicRoute && (userId == null || userId <= 0)) {
            error(response, 401, "UNAUTHORIZED", "Authentication is required");
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            long maxBytes = "/api/passes".equals(path) ? 2L * 1024 * 1024 : 1024L * 1024;
            if (request.getContentLengthLong() > maxBytes) {
                error(response, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds the configured limit");
                return;
            }
            chain.doFilter(new BoundedRequestWrapper(request, maxBytes), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
