package ca.aksentiev.emailfilter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.imap} — connection and timing settings for IMAP IDLE monitoring.
 * <p>
 * All timeouts are in milliseconds. Defaults match RFC 2177 recommendations
 * and standard IMAP client behavior.
 */
@ConfigurationProperties(prefix = "emailfilter.imap")
public class ImapProperties {

    private int port = 993;
    private long socketTimeout = 30_000;
    private long connectionTimeout = 15_000;
    private long idleInterval = 1_680_000; // 28 minutes
    private long initialBackoff = 1_000;
    private long maxBackoff = 300_000; // 5 minutes
    private long shutdownTimeout = 5_000;

    public ImapProperties() {
    }

    public ImapProperties(int port, long socketTimeout, long connectionTimeout,
                          long idleInterval, long initialBackoff, long maxBackoff,
                          long shutdownTimeout) {
        this.port = port;
        this.socketTimeout = socketTimeout;
        this.connectionTimeout = connectionTimeout;
        this.idleInterval = idleInterval;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.shutdownTimeout = shutdownTimeout;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(long socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public long getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public long getIdleInterval() {
        return idleInterval;
    }

    public void setIdleInterval(long idleInterval) {
        this.idleInterval = idleInterval;
    }

    public long getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(long initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public long getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(long maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public long getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(long shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }
}
