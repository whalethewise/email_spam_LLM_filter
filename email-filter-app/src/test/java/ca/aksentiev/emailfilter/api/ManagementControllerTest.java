package ca.aksentiev.emailfilter.api;

import ca.aksentiev.emailfilter.filter.spam.SpamFilter;
import ca.aksentiev.emailfilter.llm.LlmScoringService;
import ca.aksentiev.emailfilter.preprocessor.PreProcessorService;
import ca.aksentiev.emailfilter.scan.ScanResult;
import ca.aksentiev.emailfilter.scan.ScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementControllerTest {

    @Mock
    private ScanService scanService;

    @Mock
    private PreProcessorService preProcessorService;

    @Mock
    private SpamFilter spamFilter;

    @Mock
    private LlmScoringService llmScoringService;

    @InjectMocks
    private ManagementController controller;

    @Test
    void reloadReturnsSuccess() {
        ResponseEntity<Map<String, Object>> response = controller.reload();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
    }

    @Test
    void scanReturnsResultOnSuccess() {
        when(scanService.isScanning()).thenReturn(false);
        when(scanService.scan()).thenReturn(new ScanResult(1, 50, 0, "Scan complete"));

        ResponseEntity<Map<String, Object>> response = controller.scan();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("emailsEnqueued", 50);
        assertThat(response.getBody()).containsEntry("accountsScanned", 1);
    }

    @Test
    void scanReturns409WhenAlreadyRunning() {
        when(scanService.isScanning()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.scan();

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    void scanReportsFailures() {
        when(scanService.isScanning()).thenReturn(false);
        when(scanService.scan()).thenReturn(new ScanResult(2, 45, 3, "Scan complete with errors"));

        ResponseEntity<Map<String, Object>> response = controller.scan();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("failures", 3);
    }
}
