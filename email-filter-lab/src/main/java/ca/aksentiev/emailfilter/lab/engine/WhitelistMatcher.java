package ca.aksentiev.emailfilter.lab.engine;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Checks an email sender against a whitelist configuration.
 * Supports exact address match, exact domain match, and wildcard patterns.
 */
@Component
public class WhitelistMatcher {

    public boolean matches(String senderAddress, WhitelistConfig whitelist) {
        if (whitelist == null || senderAddress == null || senderAddress.isBlank()) {
            return false;
        }
        String lower = senderAddress.toLowerCase();
        String domain = extractDomain(lower);

        for (String address : whitelist.addresses()) {
            if (lower.equalsIgnoreCase(address)) {
                return true;
            }
        }
        for (String d : whitelist.domains()) {
            if (domain.equalsIgnoreCase(d)) {
                return true;
            }
        }
        for (String pattern : whitelist.patterns()) {
            if (matchesWildcard(lower, pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String extractDomain(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1) : email;
    }

    private boolean matchesWildcard(String value, String pattern) {
        String regex = Pattern.quote(pattern).replace("\\*", ".*");
        return value.matches(regex);
    }
}
