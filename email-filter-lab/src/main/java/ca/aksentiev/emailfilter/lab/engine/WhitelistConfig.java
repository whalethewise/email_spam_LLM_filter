package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

public record WhitelistConfig(
        List<String> addresses,
        List<String> domains,
        List<String> patterns) {

    public WhitelistConfig {
        addresses = addresses != null ? addresses : List.of();
        domains = domains != null ? domains : List.of();
        patterns = patterns != null ? patterns : List.of();
    }
}
