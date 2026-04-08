package ca.aksentiev.emailfilter.spamassassin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.aksentiev.emailfilter.config.SpamAssassinProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Communicates with the SpamAssassin daemon via the spamc protocol.
 * Layer 2 of the spam scoring pipeline.
 * <p>
 * Never throws exceptions to callers — returns {@link SpamAssassinResult#unavailable()}
 * when the daemon is unreachable.
 */
@Service
public class SpamAssassinClient {

    private static final Logger log = LoggerFactory.getLogger(SpamAssassinClient.class);

    private static final Pattern SPAM_LINE_PATTERN =
            Pattern.compile("Spam:\\s*(True|False)\\s*;\\s*([\\d.]+)\\s*/\\s*[\\d.]+", Pattern.CASE_INSENSITIVE);
    private static final double MAX_RAW_SCORE = 20.0;
    private static final double MAX_NORMALIZED_SCORE = 10.0;

    private final SpamAssassinProperties properties;

    public SpamAssassinClient(SpamAssassinProperties properties) {
        this.properties = properties;
        log.info("SpamAssassin client configured: host={}, port={}, timeout={}ms",
                properties.host(), properties.port(), properties.timeout());
    }

    /**
     * Sends raw email content to SpamAssassin and returns the result.
     *
     * @param rawEmail the raw email content (headers + body)
     * @return parsed result, or an unavailable result if SA cannot be reached
     */
    public SpamAssassinResult check(String rawEmail) {
        try {
            String response = sendToSpamd(rawEmail);
            return parseResponse(response);
        } catch (IOException e) {
            log.warn("SpamAssassin unavailable at {}:{} — {}", properties.host(), properties.port(), e.getMessage());
            return SpamAssassinResult.unavailable();
        }
    }

    String sendToSpamd(String rawEmail) throws IOException {
        byte[] emailBytes = rawEmail.getBytes(StandardCharsets.UTF_8);
        String request = "SYMBOLS SPAMC/1.5\r\n" + "Content-length: " + emailBytes.length + "\r\n" + "\r\n";

        try (Socket socket = new Socket()) {
            socket.connect(
                    new java.net.InetSocketAddress(properties.host(), properties.port()), properties.timeout());
            socket.setSoTimeout(properties.timeout());

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.write(emailBytes);
            out.flush();

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
            return response.toString();
        }
    }

    SpamAssassinResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Empty response from SpamAssassin");
            return SpamAssassinResult.unavailable();
        }

        String[] lines = response.split("\n");
        boolean isSpam = false;
        double rawScore = 0.0;
        List<String> rules = List.of();

        for (String line : lines) {
            Matcher spamMatcher = SPAM_LINE_PATTERN.matcher(line);
            if (spamMatcher.find()) {
                isSpam = spamMatcher.group(1).equalsIgnoreCase("True");
                rawScore = Double.parseDouble(spamMatcher.group(2));
                continue;
            }
            // Rules line: comes after the blank line separator, contains comma-separated rule names
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("SPAMD/") && !trimmed.startsWith("Spam:")
                    && !trimmed.startsWith("Content-length:")) {
                rules = parseRules(trimmed);
            }
        }

        double normalizedScore = normalizeScore(rawScore);
        return new SpamAssassinResult(rawScore, normalizedScore, isSpam, rules, true);
    }

    List<String> parseRules(String rulesLine) {
        if (rulesLine == null || rulesLine.isBlank()) {
            return List.of();
        }
        List<String> rules = new ArrayList<>();
        for (String rule : rulesLine.split(",")) {
            String trimmed = rule.trim();
            if (!trimmed.isEmpty()) {
                rules.add(trimmed);
            }
        }
        return Collections.unmodifiableList(rules);
    }

    static double normalizeScore(double rawScore) {
        if (rawScore <= 0) {
            return 1.0;
        }
        double normalized = (rawScore / MAX_RAW_SCORE) * MAX_NORMALIZED_SCORE;
        return Math.min(MAX_NORMALIZED_SCORE, Math.max(1.0, normalized));
    }
}
