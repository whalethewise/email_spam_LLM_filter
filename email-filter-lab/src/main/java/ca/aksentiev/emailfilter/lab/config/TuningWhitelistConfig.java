package ca.aksentiev.emailfilter.lab.config;

import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.filter.spam.Whitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads tuning-whitelist.yml — the whitelist shared by the lab's "review"
 * and "reshuffle" modes to skip LLM calls for known-good senders. Kept as
 * its own file (like staging-filters.yml) so it can be tuned and reused
 * independently of application.yml.
 *
 * <p>Built on the same {@link Whitelist} class the main app's spam filter
 * uses, so {@code regex:}-prefixed domains and patterns (FILTER-SPEC.md
 * §Whitelist) work here too, not just exact addresses/domains and {@code *}
 * wildcards.
 */
@Configuration
public class TuningWhitelistConfig {

    private static final Logger log = LoggerFactory.getLogger(TuningWhitelistConfig.class);

    @Bean
    @SuppressWarnings("unchecked")
    public Whitelist tuningWhitelist() {
        Map<String, Object> raw = LabYamlLoader.load("tuning-whitelist.yml");
        if (raw == null) {
            log.warn("No tuning-whitelist.yml found, using empty whitelist");
            return new Whitelist(new SpamFilterProperties.WhitelistConfig(List.of(), List.of(), List.of()));
        }

        // Same shape as a filter's `whitelist:` block in filters.yml (FILTER-SPEC.md
        // §Whitelist) — nested under a top-level "whitelist" key so entries can be
        // copy-pasted between the two verbatim.
        Object nested = raw.get("whitelist");
        Map<String, Object> whitelistMap = nested instanceof Map ? (Map<String, Object>) nested : raw;

        List<String> addresses = stringList(whitelistMap, "addresses");
        List<String> domains = stringList(whitelistMap, "domains");
        List<String> patterns = stringList(whitelistMap, "patterns");
        Whitelist whitelist = new Whitelist(new SpamFilterProperties.WhitelistConfig(addresses, domains, patterns));
        log.info("Loaded tuning whitelist: {} addresses, {} exact domains + {} domain regexes, {} patterns",
                whitelist.getAddresses().size(), whitelist.getDomains().size(),
                whitelist.getDomainPatterns().size(), whitelist.getPatterns().size());
        return whitelist;
    }

    private List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
