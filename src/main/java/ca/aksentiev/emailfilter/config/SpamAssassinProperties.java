package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.spamassassin} — connection settings for the
 * SpamAssassin daemon (Layer 2 of the spam scoring pipeline).
 *
 * @param host    SpamAssassin daemon hostname or IP
 * @param port    spamc protocol port (typically 783)
 * @param timeout connection timeout in milliseconds (default 5000)
 */
@ConfigurationProperties(prefix = "emailfilter.spamassassin")
public record SpamAssassinProperties(String host, int port, int timeout) {}
