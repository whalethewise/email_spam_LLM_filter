package ca.aksentiev.emailfilter.lab.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Executes extraction-type filters: source domain check, LLM extraction, action dispatch.
 */
@Component
public class ExtractionFilter {

    private static final Logger log = LoggerFactory.getLogger(ExtractionFilter.class);
    private static final int MAX_BODY_LENGTH = 8000;

    private final WhitelistMatcher whitelistMatcher;
    private final VariableResolver variableResolver;
    private final ChatClient chatClient;

    public ExtractionFilter(WhitelistMatcher whitelistMatcher,
                            VariableResolver variableResolver,
                            ChatClient.Builder chatClientBuilder) {
        this.whitelistMatcher = whitelistMatcher;
        this.variableResolver = variableResolver;
        this.chatClient = chatClientBuilder.build();
    }

    public FilterResult execute(FilterDefinition filter, EmailMessage email) {
        // 1. Whitelist check
        if (whitelistMatcher.matches(email.from(), filter.whitelist())) {
            return FilterResult.leave("whitelisted");
        }

        // 2. Source domain check
        String sourceName = null;
        String sourceDomain = null;
        if (filter.sourceDomains() != null && !filter.sourceDomains().isEmpty()) {
            String senderDomain = extractDomain(email.from());
            for (Map.Entry<String, String> entry : filter.sourceDomains().entrySet()) {
                if (senderDomain.equalsIgnoreCase(entry.getKey())) {
                    sourceDomain = entry.getKey();
                    sourceName = entry.getValue();
                    break;
                }
            }
            if (sourceDomain == null) {
                return FilterResult.leave("sender not in source-domains");
            }
        }

        // 3. Build variables
        Map<String, String> vars = new HashMap<>();
        vars.putAll(variableResolver.emailVariables(email));
        if (sourceName != null) {
            vars.putAll(variableResolver.sourceVariables(sourceName, sourceDomain));
        }
        if (filter.data() != null) {
            vars.putAll(variableResolver.dataVariables(filter.data()));
        }

        // 4. Truncate body
        String body = email.bodyText();
        if (body != null && body.length() > MAX_BODY_LENGTH) {
            vars.put("email.body", body.substring(0, MAX_BODY_LENGTH) + "... [truncated]");
        }

        // 5. Call LLM
        String resolvedPrompt = variableResolver.resolve(filter.prompt(), vars);

        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(filter.ollamaModel())
                .temperature(0.0)
                .disableThinking()
                .build();

        String response;
        try {
            response = chatClient
                    .prompt(resolvedPrompt)
                    .options(options)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("LLM call failed for extraction filter: {}", e.getMessage());
            return buildAlwaysResult(filter, email, vars);
        }

        // 6. Strip think blocks and trim
        response = stripThinkBlocks(response).trim();

        // 7. Determine actions
        ExtractionActions actions = filter.actions();
        if (actions == null) {
            return FilterResult.leave();
        }

        if ("NONE".equalsIgnoreCase(response)) {
            // Execute always actions only
            return buildFromActions(actions.always(), null, email, vars, null);
        }

        // LLM returned content — resolve {llm.response} in on-response actions
        vars.put("llm.response", response);
        List<ActionDefinition> allActions = new java.util.ArrayList<>();
        allActions.addAll(actions.onResponse());
        allActions.addAll(actions.always());

        return buildFromActions(allActions, response, email, vars, response);
    }

    private FilterResult buildAlwaysResult(FilterDefinition filter, EmailMessage email, Map<String, String> vars) {
        if (filter.actions() == null) {
            return FilterResult.leave();
        }
        return buildFromActions(filter.actions().always(), null, email, vars, null);
    }

    private FilterResult buildFromActions(List<ActionDefinition> actions, String llmResponse,
                                          EmailMessage email, Map<String, String> vars,
                                          String rawLlmResponse) {
        if (actions == null || actions.isEmpty()) {
            return FilterResult.leave();
        }

        // Find first destructive action for the result
        ActionDefinition primary = null;
        for (ActionDefinition action : actions) {
            if (action.type() != ActionType.LEAVE && action.type() != ActionType.FLAG) {
                primary = action;
                break;
            }
        }
        if (primary == null) {
            primary = actions.get(0);
        }

        String folder = primary.folder() != null ? variableResolver.resolve(primary.folder(), vars) : null;
        String to = primary.to() != null ? variableResolver.resolve(primary.to(), vars) : null;
        String subject = primary.subject() != null ? variableResolver.resolve(primary.subject(), vars) : null;
        String body = primary.body() != null ? variableResolver.resolve(primary.body(), vars) : null;

        return new FilterResult(
                primary.type(),
                folder,
                to, subject, body,
                0, null,
                rawLlmResponse, null);
    }

    private String extractDomain(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1) : email;
    }

    private String stripThinkBlocks(String response) {
        if (response == null) {
            return "";
        }
        return response.replaceAll("(?s)<think>.*?</think>", "").trim();
    }
}
