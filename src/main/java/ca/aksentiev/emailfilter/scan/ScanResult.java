package ca.aksentiev.emailfilter.scan;

/**
 * Summary of a completed scan operation.
 *
 * @param accountsScanned number of IMAP accounts processed
 * @param emailsEnqueued  number of emails successfully parsed and enqueued
 * @param failures        number of emails that failed to parse or accounts that failed to connect
 * @param message         human-readable summary
 */
public record ScanResult(int accountsScanned, int emailsEnqueued, int failures, String message) {}
