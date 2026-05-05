package ca.aksentiev.emailfilter.lab.engine;

/**
 * One fully variable-substituted action ready for execution.
 * All template tokens have already been resolved.
 */
public record ResolvedAction(
        ActionType type,
        String targetFolder,
        String emailTo,
        String emailSubject,
        String emailBody) {

    public static ResolvedAction of(ActionType type) {
        return new ResolvedAction(type, null, null, null, null);
    }

    public static ResolvedAction moveToFolder(String folder) {
        return new ResolvedAction(ActionType.MOVE_TO_FOLDER, folder, null, null, null);
    }

    public static ResolvedAction sendEmail(String to, String subject, String body) {
        return new ResolvedAction(ActionType.SEND_EMAIL, null, to, subject, body);
    }
}
