package ca.aksentiev.emailfilter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that filters.yml is imported via spring.config.import and
 * its definitions are bound into {@link FilterEngineProperties}.
 */
@SpringBootTest
class FilterEnginePropertiesIntegrationTest {

    @Autowired
    private FilterEngineProperties filterEngineProperties;

    @Test
    void filtersYmlIsLoadedAndBound() {
        assertThat(filterEngineProperties.getFilters())
                .as("filters.yml should be imported and its definitions bound")
                .isNotEmpty();
    }

    @Test
    void flyerFilterIsLoaded() {
        assertThat(filterEngineProperties.getFilters())
                .anyMatch(f -> "flyer-filter".equals(f.getName()));
    }
}
