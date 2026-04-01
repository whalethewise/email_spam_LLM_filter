package ca.aksentiev.emailfilter.preprocessor;

import java.util.List;

/**
 * A detected brand impersonation attempt where the brand name
 * appears only after character normalization.
 *
 * @param brandName        the known brand being impersonated
 * @param originalText     the original text containing the disguised brand name
 * @param normalizedText   the text after character normalization revealing the brand
 * @param legitimateDomains known legitimate domains for this brand
 */
public record BrandImpersonation(
        String brandName,
        String originalText,
        String normalizedText,
        List<String> legitimateDomains) {}
