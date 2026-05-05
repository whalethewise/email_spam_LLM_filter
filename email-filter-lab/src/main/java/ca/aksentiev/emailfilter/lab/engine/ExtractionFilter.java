package ca.aksentiev.emailfilter.lab.engine;

import java.util.ArrayList;
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

        ExtractionActions actions = filter.actions();
        if (actions == null) {
            return FilterResult.leave("no actions configured");
        }

        String response;
        try {
            response = chatClient
                    .prompt(resolvedPrompt)
                    .options(options)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("LLM call failed for extraction filter: {}", e.getMessage());
            // On LLM failure, run only `always` actions (defensive — same as NONE)
            List<ResolvedAction> resolved = resolveAll(actions.always(), vars);
            return new FilterResult(resolved, 0, "LLM call failed: " + e.getMessage(), null, null);
        }

        // 6. Strip think blocks and trim
        response = stripThinkBlocks(response).trim();

        // 7. Build action list — NONE skips on-response, always still runs
        boolean noResponse = response.isEmpty() || "NONE".equalsIgnoreCase(response);
        List<ActionDefinition> ordered = new ArrayList<>();
        if (!noResponse) {
            vars.put("llm.response", response);
            if (actions.onResponse() != null) {
                ordered.addAll(actions.onResponse());
            }
        }
        if (actions.always() != null) {
            ordered.addAll(actions.always());
        }

        List<ResolvedAction> resolved = resolveAll(ordered, vars);
        return new FilterResult(resolved, 0, null, noResponse ? null : response, null);
    }

    private List<ResolvedAction> resolveAll(List<ActionDefinition> actions, Map<String, String> vars) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<ResolvedAction> out = new ArrayList<>(actions.size());
        for (ActionDefinition action : actions) {
            out.add(resolve(action, vars));
        }
        return out;
    }

    private ResolvedAction resolve(ActionDefinition action, Map<String, String> vars) {
        return new ResolvedAction(
                action.type(),
                action.folder() != null ? variableResolver.resolve(action.folder(), vars) : null,
                action.to() != null ? variableResolver.resolve(action.to(), vars) : null,
                action.subject() != null ? variableResolver.resolve(action.subject(), vars) : null,
                action.body() != null ? variableResolver.resolve(action.body(), vars) : null);
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
