package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.scan} — controls the on-demand scan feature.
 * A scan iterates existing messages in IMAP folders and runs them through
 * the filter chain in dry-run mode (never moves or deletes).
 *
 * @param enabled     whether the scan API endpoint is active (default true)
 * @param inboxOnly   if true, scan only the inbox folder; if false, scan all folders (default true)
 * @param limit       maximum messages to scan per folder, 0 = no limit (default 0)
 * @param dryRunLimit maximum messages when scan forces dry-run, 0 = no limit (default 50)
 */
@ConfigurationProperties(prefix = "emailfilter.scan")
public record ScanProperties(boolean enabled, boolean inboxOnly, int limit, int dryRunLimit) {

    public ScanProperties {
        if (limit < 0) {
            limit = 0;
        }
        if (dryRunLimit < 0) {
            dryRunLimit = 0;
        }
    }

    /**
     * Returns the effective limit: dryRunLimit for dry-run scans, limit for full scans.
     */
    public int effectiveLimit(boolean forceDryRun) {
        return forceDryRun ? dryRunLimit : limit;
    }
}
