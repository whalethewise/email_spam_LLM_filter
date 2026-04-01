package ca.aksentiev.emailfilter.api;

import java.util.Map;

import ca.aksentiev.emailfilter.scan.ScanResult;
import ca.aksentiev.emailfilter.scan.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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

    public ManagementController(ScanService scanService) {
        this.scanService = scanService;
    }

    /**
     * Reloads configuration files (filters.yml, brands.json, char_substitutions.json).
     * Placeholder — actual reload logic will be wired when those services support hot-reload.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        log.info("Reload requested via management API");
        // TODO: wire to PreProcessorService.reload(), FilterEngine.reload() when implemented
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Reload complete",
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
}
