package ca.aksentiev.emailfilter.action;

import java.util.List;

import ca.aksentiev.emailfilter.scoring.LayerScore;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectTaggerTest {

    private SubjectTagger tagger;

    @BeforeEach
    void setUp() {
        tagger = new SubjectTagger();
    }

    @Test
    void tagsSubjectWithScoreTag() {
        ScoreResult score = scoreResult("[PP:3/SA:8/LLM:9=8]");

        String result = tagger.tag("Important meeting tomorrow", score);

        assertThat(result).isEqualTo("[PP:3/SA:8/LLM:9=8] Important meeting tomorrow");
    }

    @Test
    void replacesExistingTag() {
        ScoreResult score = scoreResult("[PP:5/SA:7/LLM:8=7]");

        String result = tagger.tag("[PP:1/SA:2/LLM:3=2] Old tagged subject", score);

        assertThat(result).isEqualTo("[PP:5/SA:7/LLM:8=7] Old tagged subject");
    }

    @Test
    void handlesNullSubject() {
        ScoreResult score = scoreResult("[PP:1/SA:1/LLM:1=1]");

        String result = tagger.tag(null, score);

        assertThat(result).isEqualTo("[PP:1/SA:1/LLM:1=1] (no subject)");
    }

    @Test
    void handlesEmptySubject() {
        ScoreResult score = scoreResult("[PP:2/SA:3/LLM:4=3]");

        String result = tagger.tag("", score);

        assertThat(result).isEqualTo("[PP:2/SA:3/LLM:4=3] (no subject)");
    }

    @Test
    void handlesBlankSubject() {
        ScoreResult score = scoreResult("[PP:2/SA:3/LLM:4=3]");

        String result = tagger.tag("   ", score);

        assertThat(result).isEqualTo("[PP:2/SA:3/LLM:4=3] (no subject)");
    }

    @Test
    void preservesSubjectWithoutExistingTag() {
        ScoreResult score = scoreResult("[PP:1/SA:-/LLM:2=2]");

        String result = tagger.tag("Re: Your order confirmation", score);

        assertThat(result).isEqualTo("[PP:1/SA:-/LLM:2=2] Re: Your order confirmation");
    }

    private ScoreResult scoreResult(String tag) {
        return new ScoreResult(5.0, ScoreCategory.REVIEW, List.of(), tag, "test reason");
    }
}
