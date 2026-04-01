package ca.aksentiev.emailfilter.filter.spam;

import java.util.List;

import ca.aksentiev.emailfilter.config.SpamFilterProperties.WhitelistConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelistTest {

    @Test
    void matchesExactAddress() {
        Whitelist wl = new Whitelist(new WhitelistConfig(List.of("ceo@company.com"), List.of(), List.of()));

        assertThat(wl.isWhitelisted("ceo@company.com")).isTrue();
        assertThat(wl.isWhitelisted("CEO@Company.com")).isTrue();
        assertThat(wl.isWhitelisted("other@company.com")).isFalse();
    }

    @Test
    void matchesExactDomain() {
        Whitelist wl = new Whitelist(new WhitelistConfig(List.of(), List.of("company.com"), List.of()));

        assertThat(wl.isWhitelisted("anyone@company.com")).isTrue();
        assertThat(wl.isWhitelisted("anyone@evil.com")).isFalse();
    }

    @Test
    void matchesWildcardPattern() {
        Whitelist wl = new Whitelist(new WhitelistConfig(List.of(), List.of(), List.of("*@*.gov.ca")));

        assertThat(wl.isWhitelisted("info@health.gov.ca")).isTrue();
        assertThat(wl.isWhitelisted("alert@cra.gov.ca")).isTrue();
        assertThat(wl.isWhitelisted("scam@gov.ca.evil.com")).isFalse();
    }

    @Test
    void returnsFalseForNull() {
        Whitelist wl = new Whitelist(new WhitelistConfig(List.of("a@b.com"), List.of(), List.of()));
        assertThat(wl.isWhitelisted(null)).isFalse();
    }

    @Test
    void emptyWhitelistMatchesNothing() {
        Whitelist wl = new Whitelist(new WhitelistConfig(List.of(), List.of(), List.of()));
        assertThat(wl.isWhitelisted("anyone@anywhere.com")).isFalse();
    }
}
