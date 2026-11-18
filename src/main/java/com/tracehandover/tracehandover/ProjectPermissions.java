package com.tracehandover.tracehandover;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectPermissions {
    private final TraceRepository repository;

    public ProjectPermissions(TraceRepository repository) { this.repository = repository; }

    public TraceRepository.Account account(Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return repository.accountByUsername(AccountController.normalizeUsername(principal.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public TraceRepository.Project member(long projectId, Principal principal) {
        var account = account(principal);
        // Do not reveal whether a project exists to users who are not members.
        return repository.projectFor(projectId, account.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public TraceRepository.Project coordinator(long projectId, Principal principal) {
        var project = member(projectId, principal);
        if (!"COORDINATOR".equals(project.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return project;
    }

    public TraceRepository.Project recipient(long projectId, Principal principal) {
        var project = member(projectId, principal);
        if (!"RECIPIENT".equals(project.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return project;
    }
}
