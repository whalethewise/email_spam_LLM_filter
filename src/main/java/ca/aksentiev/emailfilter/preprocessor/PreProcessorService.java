package ca.aksentiev.emailfilter.preprocessor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Layer 1 of the spam scoring pipeline. Analyzes email content for
 * suspicious patterns: character substitution tricks, brand impersonation,
 * and suspicious URLs. Returns structured findings for the LLM layer.
 */
@Service
public class PreProcessorService {

    private static final Logger log = LoggerFactory.getLogger(PreProcessorService.class);

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern IP_HOST_PATTERN =
            Pattern.compile("^https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final Set<String> SUSPICIOUS_TLDS =
            Set.of(".xyz", ".top", ".click", ".loan", ".win", ".gq", ".tk", ".ml", ".cf", ".ga", ".buzz", ".icu");
    private static final int MAX_SUBDOMAIN_LEVELS = 3;

    private static final Set<Integer> ZERO_WIDTH_CODEPOINTS =
            Set.of(0x200B, 0x200C, 0x200D, 0xFEFF);

    private final SpamFilterProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private volatile Map<String, String> charSubstitutions = Map.of();
    private volatile List<BrandEntry> brands = List.of();
    private volatile Set<Integer> spoofCodepoints = Set.of();

    public PreProcessorService(
            SpamFilterProperties properties, ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        reload();
    }

    /**
     * Reloads brands.json and char_substitutions.json from configured paths.
     * Logs warnings and uses empty defaults if files cannot be loaded.
     */
    public void reload() {
        this.charSubstitutions = loadCharSubstitutions();
        this.spoofCodepoints = buildSpoofCodepoints();
        this.brands = loadBrands();
    }

    /**
     * Analyzes a parsed email for suspicious patterns.
     *
     * @param email the parsed email to analyze
     * @return structured findings including normalized text, impersonations, URLs, and score
     */
    public PreProcessorFindings analyze(ParsedEmail email) {
        boolean zeroWidth = detectZeroWidthChars(email.subject()) || detectZeroWidthChars(email.body());
        boolean unicodeSpoofing = detectUnicodeSpoofing(email.subject()) || detectUnicodeSpoofing(email.body());

        String normalizedSubject = normalize(email.subject());
        String normalizedBody = normalize(email.body());

        List<BrandImpersonation> impersonations = detectBrandImpersonations(email, normalizedSubject, normalizedBody);
        List<SuspiciousUrl> suspiciousUrls = analyzeUrls(email.body());

        double score = calculateScore(impersonations, suspiciousUrls, zeroWidth, unicodeSpoofing);

        return new PreProcessorFindings(
                normalizedSubject, normalizedBody, impersonations, suspiciousUrls, zeroWidth, unicodeSpoofing, score);
    }

    String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String replacement = charSubstitutions.get(ch);
            result.append(replacement != null ? replacement : ch);
        }
        return result.toString();
    }

    boolean detectZeroWidthChars(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(ZERO_WIDTH_CODEPOINTS::contains);
    }

    boolean detectUnicodeSpoofing(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(spoofCodepoints::contains);
    }

    List<BrandImpersonation> detectBrandImpersonations(ParsedEmail email, String normalizedSubject, String normalizedBody) {
        String senderDomain = extractDomain(email.from());
        String combinedOriginal = (email.subject() + " " + email.body()).toLowerCase();
        String combinedNormalized = (normalizedSubject + " " + normalizedBody).toLowerCase();

        List<BrandImpersonation> impersonations = new ArrayList<>();

        for (BrandEntry brand : brands) {
            String brandLower = brand.name().toLowerCase();

            if (brand.domains().contains(senderDomain)) {
                continue;
            }

            boolean inNormalized = combinedNormalized.contains(brandLower);
            boolean inOriginal = combinedOriginal.contains(brandLower);

            if (inNormalized && !inOriginal) {
                String snippet = findSnippet(combinedOriginal, combinedNormalized, brandLower);
                String normalizedSnippet = findSnippet(combinedNormalized, combinedNormalized, brandLower);
                impersonations.add(new BrandImpersonation(brand.name(), snippet, normalizedSnippet, brand.domains()));
            }
        }
        return Collections.unmodifiableList(impersonations);
    }

    List<SuspiciousUrl> analyzeUrls(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        List<SuspiciousUrl> suspicious = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(body);

        while (matcher.find()) {
            String url = matcher.group();
            checkUrl(url, suspicious);
        }
        return Collections.unmodifiableList(suspicious);
    }

    private void checkUrl(String url, List<SuspiciousUrl> results) {
        if (IP_HOST_PATTERN.matcher(url).find()) {
            results.add(new SuspiciousUrl(url, "IP address URL"));
            return;
        }

        String host = extractHost(url);
        if (host == null) {
            return;
        }

        String hostLower = host.toLowerCase();
        for (String tld : SUSPICIOUS_TLDS) {
            if (hostLower.endsWith(tld)) {
                results.add(new SuspiciousUrl(url, "suspicious TLD: " + tld));
                return;
            }
        }

        long dotCount = hostLower.chars().filter(c -> c == '.').count();
        if (dotCount > MAX_SUBDOMAIN_LEVELS) {
            results.add(new SuspiciousUrl(url, "excessive subdomains (" + dotCount + " levels)"));
            return;
        }

        for (BrandEntry brand : brands) {
            String brandLower = brand.name().toLowerCase();
            if (hostLower.contains(brandLower) && !brand.domains().stream().anyMatch(hostLower::endsWith)) {
                results.add(new SuspiciousUrl(url, "brand name '" + brand.name() + "' in non-legitimate domain"));
                return;
            }
        }
    }

    double calculateScore(
            List<BrandImpersonation> impersonations,
            List<SuspiciousUrl> suspiciousUrls,
            boolean zeroWidth,
            boolean unicodeSpoofing) {
        double score = 1.0;
        if (!impersonations.isEmpty()) {
            score += 3.0;
        }
        if (unicodeSpoofing) {
            score += 2.0;
        }
        if (zeroWidth) {
            score += 2.0;
        }
        score += Math.min(suspiciousUrls.size(), 3);
        return Math.min(score, 10.0);
    }

    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            return "";
        }
        return email.substring(email.lastIndexOf('@') + 1).toLowerCase();
    }

    private String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String findSnippet(String source, String normalized, String brandLower) {
        int idx = normalized.indexOf(brandLower);
        if (idx < 0 || idx + brandLower.length() > source.length()) {
            return brandLower;
        }
        return source.substring(idx, Math.min(idx + brandLower.length(), source.length()));
    }

    private Map<String, String> loadCharSubstitutions() {
        String path = properties.getCharSubstitutionsPath();
        if (path == null || path.isBlank()) {
            log.warn("No char_substitutions.json path configured, using empty defaults");
            return Map.of();
        }
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            Map<String, String> loaded = objectMapper.readValue(is, new TypeReference<LinkedHashMap<String, String>>() {});
            log.info("Loaded {} character substitutions from {}", loaded.size(), path);
            return Collections.unmodifiableMap(loaded);
        } catch (IOException e) {
            log.warn("Failed to load char_substitutions.json from {}, using empty defaults", path, e);
            return Map.of();
        }
    }

    private Set<Integer> buildSpoofCodepoints() {
        Set<Integer> codepoints = new java.util.HashSet<>();
        for (String key : charSubstitutions.keySet()) {
            if (key.length() == 1) {
                int cp = key.codePointAt(0);
                if (Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC
                        || Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.GREEK) {
                    codepoints.add(cp);
                }
            }
        }
        return Collections.unmodifiableSet(codepoints);
    }

    private List<BrandEntry> loadBrands() {
        String path = properties.getBrandsPath();
        if (path == null || path.isBlank()) {
            log.warn("No brands.json path configured, using empty defaults");
            return List.of();
        }
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode brandsNode = root.get("brands");
            if (brandsNode == null || !brandsNode.isArray()) {
                log.warn("brands.json has no 'brands' array, using empty defaults");
                return List.of();
            }
            List<BrandEntry> loaded = new ArrayList<>();
            for (JsonNode brandNode : brandsNode) {
                String name = brandNode.get("name").asText();
                List<String> domains = objectMapper.convertValue(brandNode.get("domains"), new TypeReference<>() {});
                loaded.add(new BrandEntry(name, domains));
            }
            log.info("Loaded {} brands from {}", loaded.size(), path);
            return Collections.unmodifiableList(loaded);
        } catch (IOException e) {
            log.warn("Failed to load brands.json from {}, using empty defaults", path, e);
            return List.of();
        }
    }

    record BrandEntry(String name, List<String> domains) {}
}
