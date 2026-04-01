package ca.aksentiev.emailfilter.preprocessor;

import java.util.List;

/**
 * Immutable result of pre-processor analysis (Layer 1),
 * carrying normalized text, detected impersonations, suspicious URLs,
 * and an overall pre-processor spam score.
 *
 * @param normalizedSubject      subject after character normalization
 * @param normalizedBody         body after character normalization
 * @param brandImpersonations    detected brand impersonation attempts
 * @param suspiciousUrls         flagged suspicious URLs
 * @param zeroWidthCharsDetected true if zero-width characters were found
 * @param unicodeSpoofingDetected true if Cyrillic/Greek lookalike characters were found
 * @param score                  pre-processor spam score (1-10)
 */
public record PreProcessorFindings(
        String normalizedSubject,
        String normalizedBody,
        List<BrandImpersonation> brandImpersonations,
        List<SuspiciousUrl> suspiciousUrls,
        boolean zeroWidthCharsDetected,
        boolean unicodeSpoofingDetected,
        double score) {}
