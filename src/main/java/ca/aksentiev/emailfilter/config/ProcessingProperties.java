package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.processing} — tuning knobs for the email processing queue.
 *
 * @param consumerThreads number of threads consuming from the processing queue (default 1)
 */
@ConfigurationProperties(prefix = "emailfilter.processing")
public record ProcessingProperties(int consumerThreads) {

    public ProcessingProperties {
        if (consumerThreads <= 0) {
            consumerThreads = 1;
        }
    }
}
