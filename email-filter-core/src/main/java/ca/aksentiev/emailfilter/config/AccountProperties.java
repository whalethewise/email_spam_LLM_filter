package ca.aksentiev.emailfilter.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code emailfilter.accounts} — the list of IMAP accounts to monitor.
 * Each account specifies connection details, target folders, and which filters apply.
 */
@ConfigurationProperties(prefix = "emailfilter")
public class AccountProperties {

    private List<Account> accounts;

    public AccountProperties(List<Account> accounts) {
        this.accounts = accounts;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    /**
     * A single IMAP account configuration.
     */
    public static class Account {

        private String name;
        private String host;
        private String username;
        private String password;
        private List<String> filters;
        private Folders folders;

        public Account(String name, String host, String username, String password,
                       List<String> filters, Folders folders) {
            this.name = name;
            this.host = host;
            this.username = username;
            this.password = password;
            this.filters = filters;
            this.folders = folders;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public List<String> getFilters() {
            return filters;
        }

        public Folders getFolders() {
            return folders;
        }
    }

    /**
     * IMAP folder paths for an account.
     *
     * @param inbox  folder to monitor for new mail
     * @param review folder for emails needing human review (e.g. medium-score spam)
     * @param junk   folder for confirmed spam / junk
     */
    public record Folders(String inbox, String review, String junk) {
    }
}
