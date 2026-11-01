package com.lld.jiralite;

import com.lld.popularity.Ranked;
import com.lld.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The front door of Jira-lite. Every method that takes a {@code user} checks permission first, always in the same order: BROWSE (can you see it), then the action right, then the workflow's own rules.
 *
 * <p>Error contract:
 * <ul>
 * <li>{@code IssueNotFound}: the issue does not exist, is deleted, or the user may not browse its project (the three look the same).
 * <li>{@code PermissionDenied}: the user can see the project but lacks the action right (or may not even browse a project they name directly, like in create and trending).
 * <li>{@code ProjectNotFound}: the permission check passed but the project was never created.
 * <li>{@code WorkflowException}: the engine refused (illegal move, guard, stale version). Nothing changed.
 * <li>{@code IndexUnavailable}: search could not be brought up to date. Nothing changed.
 * </ul>
 */
public interface Jira {

    /** Registers a project with its workflow. A platform set-up call, not permission checked. */
    void createProject(String project, WorkflowDefinition definition);

    /** Creates an issue with the next key of the project. Needs BROWSE and CREATE. A null assignee means "nobody". */
    IssueDetails createIssue(String user, String project, String title, String assignee, Set<String> labels);

    /** Shows an issue and counts the view for trending. */
    IssueDetails get(String user, String issueId);

    /** Runs one workflow transition. Needs BROWSE and TRANSITION. */
    IssueDetails transition(String user, String issueId, String transitionName, long expectedVersion);

    /** Runs one transition on many issues, each checked on its own; one bad item never stops the others. */
    Map<String, Outcome> bulkTransition(String user, List<String> issueIds, String transitionName);

    /** Adds a comment. Needs BROWSE and COMMENT. */
    void comment(String user, String issueId, String text);

    /** Changes the assignee (null means nobody). Needs BROWSE and TRANSITION. */
    void assign(String user, String issueId, String assignee);

    /** Deletes an issue. Needs BROWSE and ADMINISTER. */
    void delete(String user, String issueId);

    /** Moves an issue to another project under a new key. Needs BROWSE and ADMINISTER in the source, BROWSE and CREATE in the target. */
    IssueDetails move(String user, String issueId, String targetProject);

    /** Moves every issue of a project to a new workflow version. Needs BROWSE and ADMINISTER. Returns how many issues the engine moved. */
    int migrate(String user, String project, WorkflowDefinition next, Map<String, String> stateMapping);

    /** Searches only projects the user may browse. Status and assignee match exactly (case-sensitive); labels are lower-cased on both sides; text matches whole words, all of them. */
    List<String> search(String user, String status, String assignee, String label, String text);

    /** Brings the search index up to date now. */
    void refresh();

    /** Top issues of a project in the trending window. Needs BROWSE. */
    List<Ranked> trendingIssues(String user, String project, int k);

    /** Top labels of a project in the trending window. Needs BROWSE. */
    List<Ranked> trendingLabels(String user, String project, int k);
}
