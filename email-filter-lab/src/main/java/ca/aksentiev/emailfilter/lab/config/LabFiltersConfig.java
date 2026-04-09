package ca.aksentiev.emailfilter.lab.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.lab.engine.ActionDefinition;
import ca.aksentiev.emailfilter.lab.engine.ActionType;
import ca.aksentiev.emailfilter.lab.engine.Condition;
import ca.aksentiev.emailfilter.lab.engine.ExtractionActions;
import ca.aksentiev.emailfilter.lab.engine.FilterDefinition;
import ca.aksentiev.emailfilter.lab.engine.FilterType;
import ca.aksentiev.emailfilter.lab.engine.LogisticsRule;
import ca.aksentiev.emailfilter.lab.engine.PreProcessorConfig;
import ca.aksentiev.emailfilter.lab.engine.ScoringActions;
import ca.aksentiev.emailfilter.lab.engine.ScoringConfig;
import ca.aksentiev.emailfilter.lab.engine.ScoringThresholds;
import ca.aksentiev.emailfilter.lab.engine.ScoringWeights;
import ca.aksentiev.emailfilter.lab.engine.SpamAssassinConfig;
import ca.aksentiev.emailfilter.lab.engine.WhitelistConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads staging-filters.yml and binds it to a Map of filter name to FilterDefinition.
 */
@Configuration
public class LabFiltersConfig {

    private static final Logger log = LoggerFactory.getLogger(LabFiltersConfig.class);

    @Bean
    public Map<String, FilterDefinition> filterDefinitions() {
        Map<String, Object> raw = loadYaml();
        if (raw == null || !raw.containsKey("filters")) {
            log.warn("No 'filters' key found in staging-filters.yml");
            return Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) raw.get("filters");
        Map<String, FilterDefinition> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> filterMap = (Map<String, Object>) entry.getValue();
            result.put(entry.getKey(), parseFilterDefinition(filterMap));
        }

        log.info("Loaded {} filter definitions from staging-filters.yml", result.size());
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml() {
        Yaml yaml = new Yaml();

        // Try file on disk first (working directory)
        File file = new File("staging-filters.yml");
        if (!file.exists()) {
            file = new File("email-filter-lab/staging-filters.yml");
        }
        if (file.exists()) {
            log.info("Loading staging-filters.yml from: {}", file.getAbsolutePath());
            try (FileInputStream fis = new FileInputStream(file)) {
                return yaml.load(fis);
            } catch (Exception e) {
                log.error("Failed to load staging-filters.yml from disk: {}", e.getMessage());
            }
        }

        // Fall back to classpath
        log.info("Loading staging-filters.yml from classpath");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("staging-filters.yml")) {
            if (is != null) {
                return yaml.load(is);
            }
        } catch (Exception e) {
            log.error("Failed to load staging-filters.yml from classpath: {}", e.getMessage());
        }

        log.error("staging-filters.yml not found on disk or classpath");
        return null;
    }

    @SuppressWarnings("unchecked")
    private FilterDefinition parseFilterDefinition(Map<String, Object> map) {
        FilterType type = parseEnum(FilterType.class, getString(map, "type"), FilterType.SCORING);
        boolean enabled = getBoolean(map, "enabled", true);
        String ollamaModel = getString(map, "ollama-model");
        WhitelistConfig whitelist = parseWhitelist((Map<String, Object>) map.get("whitelist"));
        PreProcessorConfig preProcessor = parsePreProcessor((Map<String, Object>) map.get("pre-processor"));
        SpamAssassinConfig spamAssassin = parseSpamAssassin((Map<String, Object>) map.get("spamassassin"));
        ScoringConfig scoring = parseScoringConfig((Map<String, Object>) map.get("scoring"));
        Map<String, String> sourceDomains = parseSourceDomains((Map<String, Object>) map.get("source-domains"));
        Map<String, Object> data = (Map<String, Object>) map.get("data");
        String prompt = getString(map, "prompt");
        ExtractionActions actions = parseExtractionActions((Map<String, Object>) map.get("actions"));
        List<LogisticsRule> rules = parseRules((List<Map<String, Object>>) map.get("rules"));

        return new FilterDefinition(type, enabled, ollamaModel, whitelist, preProcessor,
                spamAssassin, scoring, sourceDomains, data, prompt, actions, rules);
    }

    @SuppressWarnings("unchecked")
    private WhitelistConfig parseWhitelist(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new WhitelistConfig(
                getStringList(map, "addresses"),
                getStringList(map, "domains"),
                getStringList(map, "patterns"));
    }

    private PreProcessorConfig parsePreProcessor(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new PreProcessorConfig(
                getBoolean(map, "enabled", false),
                getString(map, "brands-file"),
                getString(map, "char-substitutions-file"));
    }

    private SpamAssassinConfig parseSpamAssassin(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new SpamAssassinConfig(
                getBoolean(map, "enabled", false),
                getString(map, "host"),
                getInt(map, "port", 783),
                getDouble(map, "skip-llm-above-score", 12.0));
    }

    @SuppressWarnings("unchecked")
    private ScoringConfig parseScoringConfig(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> weightsMap = (Map<String, Object>) map.get("weights");
        Map<String, Object> thresholdsMap = (Map<String, Object>) map.get("thresholds");
        Map<String, Object> actionsMap = (Map<String, Object>) map.get("actions");

        ScoringWeights weights = weightsMap != null
                ? new ScoringWeights(
                        getDouble(weightsMap, "preprocessor", 0.2),
                        getDouble(weightsMap, "spamassassin", 0.35),
                        getDouble(weightsMap, "llm", 0.45))
                : null;

        ScoringThresholds thresholds = thresholdsMap != null
                ? new ScoringThresholds(
                        getInt(thresholdsMap, "safe-max", 3),
                        getInt(thresholdsMap, "review-max", 6))
                : null;

        ScoringActions actions = actionsMap != null
                ? new ScoringActions(
                        getString(actionsMap, "safe"),
                        getString(actionsMap, "review"),
                        getString(actionsMap, "spam"))
                : null;

        return new ScoringConfig(weights, thresholds, actions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseSourceDomains(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ExtractionActions parseExtractionActions(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        List<ActionDefinition> onResponse = parseActionDefinitions(
                (List<Map<String, Object>>) map.get("on-response"));
        List<ActionDefinition> always = parseActionDefinitions(
                (List<Map<String, Object>>) map.get("always"));
        return new ExtractionActions(onResponse, always);
    }

    private List<ActionDefinition> parseActionDefinitions(List<Map<String, Object>> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream().map(this::parseActionDefinition).toList();
    }

    private ActionDefinition parseActionDefinition(Map<String, Object> map) {
        ActionType type = parseActionType(getString(map, "type"));
        return new ActionDefinition(type, getString(map, "folder"),
                getString(map, "to"), getString(map, "subject"), getString(map, "body"));
    }

    private ActionType parseActionType(String value) {
        if (value == null) {
            return ActionType.LEAVE;
        }
        return switch (value.toLowerCase()) {
            case "leave" -> ActionType.LEAVE;
            case "flag" -> ActionType.FLAG;
            case "move-to-junk" -> ActionType.MOVE_TO_JUNK;
            case "move-to-folder" -> ActionType.MOVE_TO_FOLDER;
            case "delete" -> ActionType.DELETE;
            case "send-email" -> ActionType.SEND_EMAIL;
            default -> ActionType.LEAVE;
        };
    }

    @SuppressWarnings("unchecked")
    private List<LogisticsRule> parseRules(List<Map<String, Object>> list) {
        if (list == null) {
            return null;
        }
        return list.stream().map(this::parseLogisticsRule).toList();
    }

    @SuppressWarnings("unchecked")
    private LogisticsRule parseLogisticsRule(Map<String, Object> map) {
        Condition condition = parseCondition((Map<String, Object>) map.get("condition"));
        List<ActionDefinition> actions = parseActionDefinitions(
                (List<Map<String, Object>>) map.get("actions"));
        return new LogisticsRule(condition, actions);
    }

    @SuppressWarnings("unchecked")
    private Condition parseCondition(Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        List<Condition> allOf = null;
        List<Condition> anyOf = null;
        Condition not = null;

        if (map.containsKey("all-of")) {
            allOf = ((List<Map<String, Object>>) map.get("all-of")).stream()
                    .map(this::parseCondition).toList();
        }
        if (map.containsKey("any-of")) {
            anyOf = ((List<Map<String, Object>>) map.get("any-of")).stream()
                    .map(this::parseCondition).toList();
        }
        if (map.containsKey("not")) {
            not = parseCondition((Map<String, Object>) map.get("not"));
        }

        return new Condition(
                getString(map, "subject-starts-with"),
                getString(map, "subject-ends-with"),
                getString(map, "subject-contains"),
                getString(map, "from-domain"),
                getString(map, "from-address"),
                getString(map, "from-address-contains"),
                allOf, anyOf, not);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value, T defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
