package ca.aksentiev.emailfilter.lab.tuning;

public record TuningScoreResult(int score, String reason) {

    public String scoreLabel() {
        if (score <= 3) {
            return "LEGITIMATE";
        }
        if (score <= 7) {
            return "BORDERLINE";
        }
        return "SPAM      ";
    }
}
