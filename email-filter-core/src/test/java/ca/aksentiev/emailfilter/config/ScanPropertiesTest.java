package ca.aksentiev.emailfilter.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanPropertiesTest {

    @Test
    void defaultValues() {
        ScanProperties props = new ScanProperties(true, true, 0, 50);
        assertThat(props.enabled()).isTrue();
        assertThat(props.inboxOnly()).isTrue();
        assertThat(props.limit()).isZero();
    }

    @Test
    void negativeLimitDefaultsToZero() {
        ScanProperties props = new ScanProperties(true, true, -5, -10);
        assertThat(props.limit()).isZero();
        assertThat(props.dryRunLimit()).isZero();
    }

    @Test
    void positiveLimitPreserved() {
        ScanProperties props = new ScanProperties(true, false, 100, 50);
        assertThat(props.limit()).isEqualTo(100);
        assertThat(props.inboxOnly()).isFalse();
    }
}
