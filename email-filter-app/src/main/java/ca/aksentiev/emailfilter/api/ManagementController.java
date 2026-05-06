package ca.aksentiev.emailfilter.api;

import java.util.Map;

import ca.aksentiev.emailfilter.filter.spam.SpamFilter;
import ca.aksentiev.emailfilter.llm.LlmScoringService;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorService;
import ca.aksentiev.emailfilter.scan.ScanResult;
import ca.aksentiev.emailfilter.scan.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API for operational tasks — config reload and on-demand scan.
 * All endpoints are secured by {@link ApiKeyFilter} (requires {@code X-Api-Key} header).
 */
@RestController
@RequestMapping("/api")
public class ManagementController {

    private static final Logger log = LoggerFactory.getLogger(ManagementController.class);

    private final ScanService scanService;
    private final PreProcessorService preProcessorService;
    private final SpamFilter spamFilter;
    private final LlmScoringService llmScoringService;

    public ManagementController(ScanService scanService, PreProcessorService preProcessorService,
                                 SpamFilter spamFilter, LlmScoringService llmScoringService) {
        this.scanService = scanService;
        this.preProcessorService = preProcessorService;
        this.spamFilter = spamFilter;
        this.llmScoringService = llmScoringService;
    }

    /**
     * Reloads configuration files: brands.json, char_substitutions.json, whitelist.yml,
     * and the LLM system prompt.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        log.info("Reload requested via management API");
        preProcessorService.reload();
        spamFilter.reloadWhitelist();
        llmScoringService.reload();
        log.info("Reload complete: preprocessor data + whitelist + LLM prompt refreshed");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Reload complete: preprocessor data + whitelist + LLM prompt refreshed",
                "timestamp", System.currentTimeMillis()));
    }

    /**
     * Initiates an on-demand scan of existing IMAP messages.
     * Always runs in dry-run mode — never moves or deletes emails.
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan() {
        log.info("Scan requested via management API");

        if (scanService.isScanning()) {
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "message", "A scan is already in progress",
                    "timestamp", System.currentTimeMillis()));
        }

        ScanResult result = scanService.scan();
        return ResponseEntity.ok(Map.of(
                "success", result.failures() == 0,
                "message", result.message(),
                "accountsScanned", result.accountsScanned(),
                "emailsEnqueued", result.emailsEnqueued(),
                "failures", result.failures(),
                "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/whitelist")
    public ResponseEntity<Map<String, Object>> whitelist() {
        var wl = spamFilter.getWhitelist();
        return ResponseEntity.ok(Map.of(
                "addresses", wl.getAddresses(),
                "domains", wl.getDomains(),
                "patterns", wl.getPatterns()));
    }

    @GetMapping("/prompt")
    public ResponseEntity<Map<String, Object>> prompt() {
        String current = llmScoringService.getSystemPrompt();
        return ResponseEntity.ok(Map.of(
                "length", current.length(),
                "prompt", current));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Unhandled exception in management API: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "Internal server error",
                "timestamp", System.currentTimeMillis()));
    }
}
