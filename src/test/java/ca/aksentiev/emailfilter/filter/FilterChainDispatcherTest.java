package ca.aksentiev.emailfilter.filter;

import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.AccountProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterChainDispatcherTest {

    @Test
    void singleFilterProcessed() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.processed(7.0, "move-to-junk", "Spam detected"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        assertThat(result.action()).isEqualTo("move-to-junk");
        assertThat(result.score()).isEqualTo(7.0);
        assertThat(result.reason()).isEqualTo("Spam detected");
    }

    @Test
    void firstCatchingFilterStopsChain() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.processed(8.0, "move-to-junk", "Spam"));
        EmailFilter flyerFilter = stubFilter("flyer-filter", true,
                FilterResult.processed(0.0, "tag", "Flyer detected"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter, flyerFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter", "flyer-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        // Spam filter catches first — flyer filter never runs
        assertThat(result.action()).isEqualTo("move-to-junk");
    }

    @Test
    void leaveActionContinuesToNextFilter() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.processed(2.0, "leave", "Clean email"));
        EmailFilter flyerFilter = stubFilter("flyer-filter", true,
                FilterResult.processed(0.0, "tag", "Flyer detected"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter, flyerFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter", "flyer-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        // Spam filter says leave → chain continues → flyer filter catches
        assertThat(result.action()).isEqualTo("tag");
    }

    @Test
    void skippedResultContinuesToNextFilter() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.skipped("Sender whitelisted"));
        EmailFilter flyerFilter = stubFilter("flyer-filter", true,
                FilterResult.processed(0.0, "tag", "Flyer detected"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter, flyerFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter", "flyer-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        assertThat(result.action()).isEqualTo("tag");
    }

    @Test
    void allFiltersPassReturnsLeave() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.processed(1.0, "leave", "Clean"));
        EmailFilter flyerFilter = stubFilter("flyer-filter", true,
                FilterResult.processed(0.0, "leave", "Not a flyer"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter, flyerFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter", "flyer-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        assertThat(result.action()).isEqualTo("leave");
        assertThat(result.reason()).isEqualTo("All filters passed");
    }

    @Test
    void disabledFilterSkipped() {
        EmailFilter spamFilter = stubFilter("spam-filter", false,
                FilterResult.processed(9.0, "delete", "Should not run"));
        EmailFilter flyerFilter = stubFilter("flyer-filter", true,
                FilterResult.processed(0.0, "tag", "Flyer"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter, flyerFilter));
        AccountProperties.Account account = accountWith(List.of("spam-filter", "flyer-filter"));

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        // Disabled spam filter skipped → flyer filter runs
        assertThat(result.action()).isEqualTo("tag");
    }

    @Test
    void unknownFilterNameHandledGracefully() {
        EmailFilter spamFilter = stubFilter("spam-filter", true,
                FilterResult.processed(2.0, "leave", "Clean"));

        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of(spamFilter));
        AccountProperties.Account account = accountWith(List.of("nonexistent-filter", "spam-filter"));

        // Should not throw — unknown filter is skipped, spam-filter runs
        FilterResult result = dispatcher.dispatch(testMessage(), account);

        assertThat(result.action()).isEqualTo("leave");
    }

    @Test
    void emptyFilterListReturnsLeave() {
        FilterChainDispatcher dispatcher = new FilterChainDispatcher(List.of());
        AccountProperties.Account account = accountWith(List.of());

        FilterResult result = dispatcher.dispatch(testMessage(), account);

        assertThat(result.action()).isEqualTo("leave");
        assertThat(result.reason()).isEqualTo("All filters passed");
    }

    private EmailMessage testMessage() {
        return new EmailMessage(
                "<test@example.com>",
                "sender@example.com",
                "Sender",
                List.of("me@example.com"),
                "Test Subject",
                "Test body",
                "",
                null,
                Map.of());
    }

    private AccountProperties.Account accountWith(List<String> filterNames) {
        return new AccountProperties.Account(
                "test-account", "imap.example.com", "user@example.com", "pass",
                filterNames, new AccountProperties.Folders("INBOX", "Review", "Junk"));
    }

    private EmailFilter stubFilter(String name, boolean enabled, FilterResult result) {
        return new EmailFilter() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public FilterResult process(EmailMessage message) {
                return result;
            }

            @Override
            public boolean isEnabled() {
                return enabled;
            }
        };
    }
}
