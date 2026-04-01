package ca.aksentiev.emailfilter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingPropertiesTest {

    @Test
    void usesProvidedThreadCount() {
        ProcessingProperties props = new ProcessingProperties(4);
        assertThat(props.consumerThreads()).isEqualTo(4);
    }

    @Test
    void zeroDefaultsToOne() {
        ProcessingProperties props = new ProcessingProperties(0);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }

    @Test
    void negativeDefaultsToOne() {
        ProcessingProperties props = new ProcessingProperties(-3);
        assertThat(props.consumerThreads()).isEqualTo(1);
    }
}
