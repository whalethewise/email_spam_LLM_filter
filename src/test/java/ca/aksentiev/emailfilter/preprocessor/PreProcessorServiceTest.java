package ca.aksentiev.emailfilter.preprocessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PreProcessorServiceTest {

    private PreProcessorService service;

    @BeforeEach
    void setUp() {
        SpamFilterProperties properties = new SpamFilterProperties(
                true,
                "mistral",
                8.0,
                "classpath:brands.json",
                "classpath:char_substitutions.json",
                new SpamFilterProperties.Weights(0.20, 0.35, 0.45),
                new SpamFilterProperties.Thresholds(3, 6),
                new SpamFilterProperties.Actions("none", "move-to-review", "move-to-junk"));
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new PreProcessorService(properties, resourceLoader, objectMapper);
        service.init();
    }

    @Test
    void normalizesCharacterSubstitutions() {
        String normalized = service.normalize("C0stc0");
        assertThat(normalized).isEqualTo("COstcO");
    }

    @Test
    void normalizesComplexSubstitutions() {
        String normalized = service.normalize("P@yP@1");
        assertThat(normalized).isEqualTo("PayPal");
    }

    @Test
    void detectsBrandImpersonationAfterNormalization() {
        ParsedEmail email = emailWith("spam@evil.com", "Your C0stc0 membership", "Click here for C0stc0 rewards");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.brandImpersonations()).hasSize(1);
        assertThat(findings.brandImpersonations().get(0).brandName()).isEqualTo("Costco");
    }

    @Test
    void skipsImpersonationCheckForLegitimateSender() {
        ParsedEmail email = emailWith("deals@costco.com", "Your C0stc0 membership", "Click here for rewards");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.brandImpersonations()).isEmpty();
    }

    @Test
    void detectsZeroWidthCharacters() {
        String bodyWithZeroWidth = "Click here\u200B to verify";
        ParsedEmail email = emailWith("spam@evil.com", "Normal subject", bodyWithZeroWidth);

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.zeroWidthCharsDetected()).isTrue();
    }

    @Test
    void detectsUnicodeSpoofing() {
        // \u0410 is Cyrillic A, \u0440 is Cyrillic r
        String subjectWithCyrillic = "\u0410m\u0430zon Order";
        ParsedEmail email = emailWith("spam@evil.com", subjectWithCyrillic, "Your order details");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.unicodeSpoofingDetected()).isTrue();
    }

    @Test
    void flagsIpAddressUrls() {
        ParsedEmail email = emailWith("spam@evil.com", "Click here", "Visit http://192.168.1.1/login now");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.suspiciousUrls()).hasSize(1);
        assertThat(findings.suspiciousUrls().get(0).reason()).isEqualTo("IP address URL");
    }

    @Test
    void flagsSuspiciousTlds() {
        ParsedEmail email = emailWith("spam@evil.com", "Deal", "Go to http://free-stuff.xyz/claim");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.suspiciousUrls()).hasSize(1);
        assertThat(findings.suspiciousUrls().get(0).reason()).contains("suspicious TLD");
    }

    @Test
    void flagsExcessiveSubdomains() {
        ParsedEmail email =
                emailWith("spam@evil.com", "Deal", "Go to http://a.b.c.d.evil.com/page");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.suspiciousUrls()).hasSize(1);
        assertThat(findings.suspiciousUrls().get(0).reason()).contains("excessive subdomains");
    }

    @Test
    void calculatesScoreCorrectly() {
        // Base 1 + brand impersonation 3 + zero-width 2 = 6
        String body = "Your C0stc0 rewards\u200B are waiting";
        ParsedEmail email = emailWith("spam@evil.com", "C0stc0 alert", body);

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.score()).isEqualTo(6.0);
    }

    @Test
    void capsScoreAtTen() {
        // Base 1 + brand 3 + unicode 2 + zero-width 2 + 3 suspicious URLs = 11 → capped at 10
        String body = "\u0410m\u0430zon\u200B deal http://1.2.3.4/a http://evil.xyz/b http://a.b.c.d.e.com/c";
        ParsedEmail email = emailWith("spam@evil.com", "Deal", body);

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.score()).isEqualTo(10.0);
    }

    @Test
    void cleanEmailScoresOne() {
        ParsedEmail email = emailWith("friend@gmail.com", "Hello", "How are you?");

        PreProcessorFindings findings = service.analyze(email);

        assertThat(findings.score()).isEqualTo(1.0);
        assertThat(findings.brandImpersonations()).isEmpty();
        assertThat(findings.suspiciousUrls()).isEmpty();
        assertThat(findings.zeroWidthCharsDetected()).isFalse();
        assertThat(findings.unicodeSpoofingDetected()).isFalse();
    }

    private ParsedEmail emailWith(String from, String subject, String body) {
        return new ParsedEmail("<test@example.com>", subject, from, "", List.of("me@example.com"), body, Map.of(), Instant.now());
    }
}
