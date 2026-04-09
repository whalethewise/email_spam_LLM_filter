package ca.aksentiev.emailfilter.lab.engine;

public record FilterResult(
        ActionType action,
        String targetFolder,
        String emailTo,
        String emailSubject,
        String emailBody,
        int score,
        String reason,
        String llmResponse,
        String matchedRule) {

    public static FilterResult leave() {
        return new FilterResult(ActionType.LEAVE, null, null, null, null, 0, null, null, null);
    }

    public static FilterResult leave(String reason) {
        return new FilterResult(ActionType.LEAVE, null, null, null, null, 0, reason, null, null);
    }
}
