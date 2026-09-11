package ca.aksentiev.emailfilter.filter.spam;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ca.aksentiev.emailfilter.config.SpamFilterProperties.WhitelistConfig;

/**
 * Per-filter whitelist supporting three tiers:
 * exact addresses, exact domains, and wildcard patterns.
 * Whitelisted emails skip this filter — other filters in the chain still run.
 */
public class Whitelist {

    private final Set<String> addresses;
    private final Set<String> domains;
    private final List<Pattern> domainPatterns;
    private final List<Pattern> patterns;

    public Whitelist(WhitelistConfig config) {
        this.addresses = config.addresses() != null
                ? config.addresses().stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet())
                : Set.of();

        List<String> rawDomains = config.domains() != null ? config.domains() : List.of();
        this.domains = rawDomains.stream()
                .filter(d -> !d.isBlank() && !d.startsWith(REGEX_PREFIX))
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
        this.domainPatterns = compileDomainRegexes(rawDomains);

        this.patterns = config.patterns() != null ? compilePatterns(config.patterns()) : List.of();
    }

    /**
     * Checks whether the given sender address matches the whitelist.
     *
     * @param senderAddress the sender's email address
     * @return true if whitelisted
     */
    public Set<String> getAddresses() { return addresses; }
    public Set<String> getDomains() { return domains; }
    public List<String> getDomainPatterns() { return domainPatterns.stream().map(Pattern::pattern).toList(); }
    public List<String> getPatterns() { return patterns.stream().map(Pattern::pattern).toList(); }

    public boolean isWhitelisted(String senderAddress) {
        if (senderAddress == null || senderAddress.isBlank()) {
            return false;
        }
        String lower = Normalizer.normalize(senderAddress, Normalizer.Form.NFKC).toLowerCase();

        if (addresses.contains(lower)) {
            return true;
        }

        String domain = extractDomain(lower);
        if (!domain.isEmpty()) {
            if (domains.contains(domain)) {
                return true;
            }
            for (Pattern domainPattern : domainPatterns) {
                if (domainPattern.matcher(domain).matches()) {
                    return true;
                }
            }
        }

        for (Pattern pattern : patterns) {
            if (pattern.matcher(lower).matches()) {
                return true;
            }
        }

        return false;
    }

    private String extractDomain(String email) {
        int at = email.lastIndexOf('@');
        return at >= 0 ? email.substring(at + 1) : "";
    }

    private static final String REGEX_PREFIX = "regex:";

    private static List<Pattern> compileDomainRegexes(List<String> domains) {
        List<Pattern> compiled = new ArrayList<>();
        for (String domain : domains) {
            if (domain.isBlank() || !domain.startsWith(REGEX_PREFIX)) continue;
            compiled.add(Pattern.compile(domain.substring(REGEX_PREFIX.length()), Pattern.CASE_INSENSITIVE));
        }
        return Collections.unmodifiableList(compiled);
    }

    private static List<Pattern> compilePatterns(List<String> wildcards) {
        List<Pattern> compiled = new ArrayList<>(wildcards.size());
        for (String wildcard : wildcards) {
            if (wildcard.isBlank()) continue;
            if (wildcard.startsWith(REGEX_PREFIX)) {
                compiled.add(Pattern.compile(wildcard.substring(REGEX_PREFIX.length()), Pattern.CASE_INSENSITIVE));
                continue;
            }
            String lower = wildcard.toLowerCase();
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append(".");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            compiled.add(Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE));
        }
        return Collections.unmodifiableList(compiled);
    }
}
