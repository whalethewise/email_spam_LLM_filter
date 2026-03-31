package ca.aksentiev.emailfilter.filter.api;

/**
 * Actions a filter can request on an email.
 */
public enum FilterAction {
    NONE,
    TAG,
    FLAG,
    MOVE,
    ARCHIVE,
    DELETE,
    FORWARD,
    SUMMARIZE
}
