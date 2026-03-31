package ca.aksentiev.emailfilter.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the filter chain definitions from {@code filters.yml}.
 * Each filter is a pure YAML definition — no code changes needed to add a new filter.
 */
@ConfigurationProperties(prefix = "emailfilter.filter-engine")
public class FilterEngineProperties {

    private List<FilterDefinition> filters;

    public FilterEngineProperties(List<FilterDefinition> filters) {
        this.filters = filters;
    }

    public List<FilterDefinition> getFilters() {
        return filters;
    }

    /**
     * A single filter definition from filters.yml.
     */
    public static class FilterDefinition {

        private String name;
        private boolean enabled;
        private String description;
        private boolean stopOnMatch;
        private String subjectTag;
        private ConditionGroup conditions;
        private FilterActions actions;

        public FilterDefinition(String name, boolean enabled, String description,
                                boolean stopOnMatch, String subjectTag,
                                ConditionGroup conditions, FilterActions actions) {
            this.name = name;
            this.enabled = enabled;
            this.description = description;
            this.stopOnMatch = stopOnMatch;
            this.subjectTag = subjectTag;
            this.conditions = conditions;
            this.actions = actions;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getDescription() {
            return description;
        }

        public boolean isStopOnMatch() {
            return stopOnMatch;
        }

        public String getSubjectTag() {
            return subjectTag;
        }

        public ConditionGroup getConditions() {
            return conditions;
        }

        public FilterActions getActions() {
            return actions;
        }
    }
}
