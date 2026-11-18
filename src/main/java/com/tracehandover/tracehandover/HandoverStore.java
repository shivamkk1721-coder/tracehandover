package com.tracehandover.tracehandover;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class HandoverStore {
    private final TraceRepository repository;
    private final ProjectPermissions permissions;
    private final ObjectMapper json;

    public HandoverStore(TraceRepository repository, ProjectPermissions permissions) {
        this.repository = repository;
        this.permissions = permissions;
        this.json = new ObjectMapper();
    }

    @Transactional
    public long approve(long projectId, Principal principal, String requestToken, HandoverSnapshot snapshot) {
        permissions.coordinator(projectId, principal);
        var actor = permissions.account(principal);
        try { UUID.fromString(requestToken); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The review session has expired.");
        }
        if (snapshot == null || snapshot.items().isEmpty() || snapshot.items().size() > 40)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select between one and 40 items.");

        repository.lockProject(projectId);
        var previousRequest = repository.versionByToken(projectId, requestToken);
        if (previousRequest.isPresent()) return previousRequest.get().id();

        var previous = repository.latestVersion(projectId);
        int nextNumber = previous.map(v -> v.number() + 1).orElse(1);
        String changes = previous.map(v -> ChangeSummary.describe(readSnapshot(v.snapshotJson()), snapshot))
                .orElse("Initial approved handover: " + snapshot.items().size() + " item(s).");
        String encoded = json.writeValueAsString(snapshot);
        if (encoded.length() > 150_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The handover is too large.");
        return repository.insertVersion(projectId, nextNumber, actor.id(), requestToken, encoded, changes);
    }

    public HandoverSnapshot readSnapshot(String encoded) {
        try { return json.readValue(encoded, HandoverSnapshot.class); }
        catch (Exception e) { throw new IllegalStateException("Stored handover could not be read", e); }
    }

}
