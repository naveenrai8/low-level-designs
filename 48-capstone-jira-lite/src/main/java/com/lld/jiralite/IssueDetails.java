package com.lld.jiralite;

import java.util.List;

/**
 * What a permitted person gets back about an issue.
 *
 * @param id the issue key, like WEB-1
 * @param project the project key
 * @param status the workflow state
 * @param version the engine's version counter (use it as the expected version of the next transition)
 * @param title the title
 * @param reporter who created it
 * @param assignee who works on it, or empty text
 * @param labels lower-case labels
 * @param comments the comments in order
 */
public record IssueDetails(String id, String project, String status, long version, String title, String reporter, String assignee, List<String> labels, List<String> comments) {
}
