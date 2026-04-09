package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.processing} — tuning knobs for the email processing queue.
 *
 * @param consumerThreads   number of threads consuming from the processing queue (default 1)
 * @param shutdownTimeoutMs time in milliseconds to wait for consumer threads during shutdown (default 5000)
 */
@ConfigurationProperties(prefix = "emailfilter.processing")
public record ProcessingProperties(int consumerThreads, long shutdownTimeoutMs) {

    public ProcessingProperties {
        if (consumerThreads <= 0) {
            consumerThreads = 1;
        }
        if (shutdownTimeoutMs <= 0) {
            shutdownTimeoutMs = 5000;
        }
    }
}
