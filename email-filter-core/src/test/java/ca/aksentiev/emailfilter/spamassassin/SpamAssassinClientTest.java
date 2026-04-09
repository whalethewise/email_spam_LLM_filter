package ca.aksentiev.emailfilter.spamassassin;

import ca.aksentiev.emailfilter.config.SpamAssassinProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpamAssassinClientTest {

    private SpamAssassinClient client;

    @BeforeEach
    void setUp() {
        SpamAssassinProperties properties = new SpamAssassinProperties("localhost", 783, 5000);
        client = new SpamAssassinClient(properties);
    }

    @Test
    void parsesSpamResponse() {
        String response = "SPAMD/1.1 0 EX_OK\r\n"
                + "Spam: True ; 15.3 / 5.0\r\n"
                + "\r\n"
                + "BAYES_99,HTML_MESSAGE,RCVD_IN_BL\n";

        SpamAssassinResult result = client.parseResponse(response);

        assertThat(result.available()).isTrue();
        assertThat(result.isSpam()).isTrue();
        assertThat(result.rawScore()).isEqualTo(15.3);
        assertThat(result.rules()).containsExactly("BAYES_99", "HTML_MESSAGE", "RCVD_IN_BL");
    }

    @Test
    void parsesCleanResponse() {
        String response = "SPAMD/1.1 0 EX_OK\r\n"
                + "Spam: False ; 1.2 / 5.0\r\n"
                + "\r\n"
                + "AWL,DKIM_SIGNED,SPF_PASS\n";

        SpamAssassinResult result = client.parseResponse(response);

        assertThat(result.available()).isTrue();
        assertThat(result.isSpam()).isFalse();
        assertThat(result.rawScore()).isEqualTo(1.2);
        assertThat(result.rules()).containsExactly("AWL", "DKIM_SIGNED", "SPF_PASS");
    }

    @Test
    void unavailableOnConnectionFailure() {
        // Port 1 is almost certainly not running SpamAssassin
        SpamAssassinProperties badProperties = new SpamAssassinProperties("127.0.0.1", 1, 500);
        SpamAssassinClient badClient = new SpamAssassinClient(badProperties);

        SpamAssassinResult result = badClient.check("Subject: test\r\n\r\nHello");

        assertThat(result.available()).isFalse();
        assertThat(result.rawScore()).isEqualTo(0.0);
        assertThat(result.normalizedScore()).isEqualTo(0.0);
        assertThat(result.rules()).isEmpty();
    }

    @Test
    void normalizesScoreCorrectly() {
        // raw 10 → (10/20)*10 = 5.0
        assertThat(SpamAssassinClient.normalizeScore(10.0)).isEqualTo(5.0);
        // raw 20 → (20/20)*10 = 10.0
        assertThat(SpamAssassinClient.normalizeScore(20.0)).isEqualTo(10.0);
        // raw 25 → (25/20)*10 = 12.5 → capped at 10
        assertThat(SpamAssassinClient.normalizeScore(25.0)).isEqualTo(10.0);
        // raw 0 → floor at 1
        assertThat(SpamAssassinClient.normalizeScore(0.0)).isEqualTo(1.0);
        // raw 2 → (2/20)*10 = 1.0
        assertThat(SpamAssassinClient.normalizeScore(2.0)).isEqualTo(1.0);
    }

    @Test
    void emptyResponseReturnsUnavailable() {
        SpamAssassinResult result = client.parseResponse("");

        assertThat(result.available()).isFalse();
    }

    @Test
    void parsesRulesLine() {
        assertThat(client.parseRules("RULE_A, RULE_B , RULE_C")).containsExactly("RULE_A", "RULE_B", "RULE_C");
        assertThat(client.parseRules("")).isEmpty();
        assertThat(client.parseRules(null)).isEmpty();
    }
}
