package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.reload} — settings for the management/reload API
 * that allows hot-reloading filters.yml and brands.json without restart.
 *
 * @param port   management port (separate from main server port, internal only)
 * @param apiKey API key for authenticating reload requests — injected via environment variable
 */
@ConfigurationProperties(prefix = "emailfilter.reload")
public record ReloadApiProperties(
        int port,
        String apiKey
) {
}
