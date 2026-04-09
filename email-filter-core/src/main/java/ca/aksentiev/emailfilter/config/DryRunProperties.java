package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.dry-run} — controls dry-run mode (Phase 0).
 * When enabled, the system runs the full pipeline but records decisions
 * instead of executing email actions, then generates a digest report.
 *
 * @param enabled      whether dry-run mode is active
 * @param reportSendTo email address to send the digest report to
 * @param reportSaveTo file path to save the report (null to skip)
 * @param schedule     cron expression for scheduled report generation (default daily at 7am)
 */
@ConfigurationProperties(prefix = "emailfilter.dry-run")
public record DryRunProperties(boolean enabled, String reportSendTo, String reportSaveTo, String schedule) {}
