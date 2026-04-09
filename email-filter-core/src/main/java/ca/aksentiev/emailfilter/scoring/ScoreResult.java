package ca.aksentiev.emailfilter.scoring;

import java.util.List;

/**
 * Final weighted score result from the three-layer spam scoring pipeline.
 *
 * @param finalScore  weighted average score (1-10)
 * @param category    classification: SAFE, REVIEW, or SPAM
 * @param layerScores individual layer contributions
 * @param subjectTag  formatted tag for subject prepend, e.g. {@code [PP:3/SA:8/LLM:9=8]}
 * @param llmReason   reason from the LLM layer (empty if LLM unavailable/skipped)
 */
public record ScoreResult(
        double finalScore,
        ScoreCategory category,
        List<LayerScore> layerScores,
        String subjectTag,
        String llmReason) {}
