package com.tracehandover.tracehandover;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Database access; callers must perform project-level authorization before using project data. */
@Repository
public class TraceRepository {
    public record Account(long id, String username, String passwordHash) {}
    public record Project(long id, String name, String departingMember, long createdBy,
                          String createdAt, String role) {}
    public record Version(long id, long projectId, int number, long createdBy,
                          String createdAt, String snapshotJson, String changeSummary) {}
    public record Acknowledgement(String username, String acknowledgedAt) {}

    private final JdbcTemplate jdbc;

    private static final RowMapper<Account> ACCOUNT = (r, n) ->
            new Account(r.getLong("id"), r.getString("username"), r.getString("password_hash"));
    private static final RowMapper<Project> PROJECT = (r, n) ->
            new Project(r.getLong("id"), r.getString("name"), r.getString("departing_member"),
                    r.getLong("created_by"), r.getString("created_at"), r.getString("member_role"));
    private static final RowMapper<Version> VERSION = (r, n) ->
            new Version(r.getLong("id"), r.getLong("project_id"), r.getInt("version_no"),
                    r.getLong("created_by"), r.getString("created_at"), r.getString("snapshot_json"),
                    r.getString("change_summary"));

    public TraceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Account> accountByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash FROM app_accounts WHERE username = ?",
                ACCOUNT, username).stream().findFirst();
    }

    public Optional<Account> accountById(long id) {
        return jdbc.query("SELECT id, username, password_hash FROM app_accounts WHERE id = ?",
                ACCOUNT, id).stream().findFirst();
    }

    public Account createAccount(String username, String passwordHash) {
        long id = insert("INSERT INTO app_accounts(username, password_hash, created_at) VALUES (?, ?, ?)",
                username, passwordHash, Instant.now().toString());
        return new Account(id, username, passwordHash);
    }

    @Transactional
    public long createProject(String name, String departingMember, long coordinatorId) {
        long projectId = insert("INSERT INTO projects(name, departing_member, created_by, created_at) VALUES (?, ?, ?, ?)",
                name, departingMember, coordinatorId, Instant.now().toString());
        jdbc.update("INSERT INTO project_members(project_id, account_id, member_role) VALUES (?, ?, 'COORDINATOR')",
                projectId, coordinatorId);
        return projectId;
    }

    public List<Project> projectsFor(long accountId) {
        return jdbc.query("""
                SELECT p.id, p.name, p.departing_member, p.created_by, p.created_at, m.member_role
                FROM projects p JOIN project_members m ON m.project_id = p.id
                WHERE m.account_id = ? ORDER BY p.id DESC
                """, PROJECT, accountId);
    }

    public Optional<Project> projectFor(long projectId, long accountId) {
        return jdbc.query("""
                SELECT p.id, p.name, p.departing_member, p.created_by, p.created_at, m.member_role
                FROM projects p JOIN project_members m ON m.project_id = p.id
                WHERE p.id = ? AND m.account_id = ?
                """, PROJECT, projectId, accountId).stream().findFirst();
    }

    public Optional<String> memberRole(long projectId, long accountId) {
        return jdbc.query("SELECT member_role FROM project_members WHERE project_id = ? AND account_id = ?",
                (r, n) -> r.getString(1), projectId, accountId).stream().findFirst();
    }

    public List<String> recipientNames(long projectId) {
        return jdbc.query("""
                SELECT a.username FROM app_accounts a JOIN project_members m ON m.account_id = a.id
                WHERE m.project_id = ? AND m.member_role = 'RECIPIENT' ORDER BY a.username
                """, (r, n) -> r.getString(1), projectId);
    }

    public void addRecipient(long projectId, long accountId) {
        jdbc.update("INSERT INTO project_members(project_id, account_id, member_role) VALUES (?, ?, 'RECIPIENT')",
                projectId, accountId);
    }

    public boolean removeRecipient(long projectId, long accountId) {
        return jdbc.update("DELETE FROM project_members WHERE project_id = ? AND account_id = ? AND member_role = 'RECIPIENT'",
                projectId, accountId) == 1;
    }

    /** Must be called inside a transaction before numbering and inserting a version. */
    public void lockProject(long projectId) {
        jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, projectId);
    }

    public Optional<Version> versionByToken(long projectId, String requestToken) {
        return jdbc.query("SELECT * FROM handover_versions WHERE project_id = ? AND request_token = ?",
                VERSION, projectId, requestToken).stream().findFirst();
    }

    public Optional<Version> latestVersion(long projectId) {
        return jdbc.query("SELECT * FROM handover_versions WHERE project_id = ? ORDER BY version_no DESC",
                VERSION, projectId).stream().findFirst();
    }

    public List<Version> versions(long projectId) {
        return jdbc.query("SELECT * FROM handover_versions WHERE project_id = ? ORDER BY version_no DESC",
                VERSION, projectId);
    }

    public Optional<Version> version(long projectId, long versionId) {
        return jdbc.query("SELECT * FROM handover_versions WHERE project_id = ? AND id = ?",
                VERSION, projectId, versionId).stream().findFirst();
    }

    public long insertVersion(long projectId, int versionNo, long createdBy, String requestToken,
                              String snapshotJson, String changeSummary) {
        return insert("""
                INSERT INTO handover_versions(project_id, version_no, created_by, request_token,
                                              created_at, snapshot_json, change_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, projectId, versionNo, createdBy, requestToken, Instant.now().toString(),
                snapshotJson, changeSummary);
    }

    @Transactional
    public void acknowledge(long versionId, long recipientId) {
        // Serialize retries so that refreshes or duplicate clicks cannot create duplicate receipts.
        jdbc.queryForObject("SELECT id FROM handover_versions WHERE id = ? FOR UPDATE", Long.class, versionId);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM handover_acknowledgements WHERE version_id = ? AND account_id = ?",
                Integer.class, versionId, recipientId);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO handover_acknowledgements(version_id, account_id, acknowledged_at) VALUES (?, ?, ?)",
                    versionId, recipientId, Instant.now().toString());
        }
    }

    public List<Acknowledgement> acknowledgements(long versionId) {
        return jdbc.query("""
                SELECT a.username, r.acknowledged_at FROM handover_acknowledgements r
                JOIN app_accounts a ON a.id = r.account_id WHERE r.version_id = ? ORDER BY r.acknowledged_at
                """, (r, n) -> new Acknowledgement(r.getString(1), r.getString(2)), versionId);
    }

    private long insert(String sql, Object... values) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Database did not return an ID");
        return key.longValue();
    }
}
