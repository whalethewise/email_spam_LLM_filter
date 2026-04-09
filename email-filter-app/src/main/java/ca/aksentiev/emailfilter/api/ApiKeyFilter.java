package ca.aksentiev.emailfilter.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import ca.aksentiev.emailfilter.config.ReloadApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code X-Api-Key} header on all {@code /api/**} requests
 * against the key configured in {@link ReloadApiProperties}.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    private final ReloadApiProperties reloadApiProperties;

    public ApiKeyFilter(ReloadApiProperties reloadApiProperties) {
        this.reloadApiProperties = reloadApiProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        String expectedKey = reloadApiProperties.apiKey();

        if (expectedKey == null || expectedKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "API key not configured");
            return;
        }

        if (providedKey == null || !MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
