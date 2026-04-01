package ca.aksentiev.emailfilter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingPropertiesTest {

    @Test
    void usesProvidedThreadCount() {
        ProcessingProperties props = new ProcessingProperties(4, 5000);
        assertThat(props.consumerThreads()).isEqualTo(4);
    }

    @Test
    void zeroThreadCountDefaultsToOne() {
        ProcessingProperties props = new ProcessingProperties(0, 5000);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void negativeThreadCountDefaultsToOne() {
        ProcessingProperties props = new ProcessingProperties(-3, 5000);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void usesProvidedShutdownTimeout() {
        ProcessingProperties props = new ProcessingProperties(1, 10000);
        assertThat(props.shutdownTimeoutMs()).isEqualTo(10000);
    }

    @Test
    void zeroShutdownTimeoutDefaultsTo5000() {
        ProcessingProperties props = new ProcessingProperties(1, 0);
        assertThat(props.shutdownTimeoutMs()).isEqualTo(5000);
    }

    @Test
    void negativeShutdownTimeoutDefaultsTo5000() {
        ProcessingProperties props = new ProcessingProperties(1, -1);
        assertThat(props.shutdownTimeoutMs()).isEqualTo(5000);
    }
}
