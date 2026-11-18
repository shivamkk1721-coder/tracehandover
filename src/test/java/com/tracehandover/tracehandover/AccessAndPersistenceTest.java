package com.tracehandover.tracehandover;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AccessAndPersistenceTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TraceRepository repository;
    @Autowired HandoverStore handovers;
    @Autowired PasswordEncoder passwords;
    @MockitoBean GeminiService gemini;
    private long projectId;
    private long versionId;
    private String requestToken;
    private Principal coordinator = () -> "coordinator";

    @BeforeEach
    void setup() throws Exception {
        when(gemini.suggest(anyList())).thenReturn(Map.of());
        jdbc.update("DELETE FROM handover_acknowledgements");
        jdbc.update("DELETE FROM handover_versions");
        jdbc.update("DELETE FROM project_members");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM app_accounts");
        var owner = repository.createAccount("coordinator", passwords.encode("demo-password-123"));
        var recipient = repository.createAccount("recipient", passwords.encode("demo-password-456"));
        repository.createAccount("stranger", passwords.encode("demo-password-789"));
        projectId = repository.createProject("Presentation", "Asha", owner.id());
        repository.addRecipient(projectId, recipient.id());
        requestToken = UUID.randomUUID().toString();
        versionId = handovers.approve(projectId, coordinator, requestToken, snapshot("In progress"));
    }

    private HandoverSnapshot snapshot(String status) {
        var evidence = List.of(new HandoverSnapshot.SourceRef("F2-L2", "tasks.csv", "Collect recordings,In progress"));
        var task = new HandoverSnapshot.Claim("Collect recordings", evidence);
        var owner = new HandoverSnapshot.Claim("Rahul", evidence);
        var statusClaim = new HandoverSnapshot.Claim(status, evidence);
        var next = new HandoverSnapshot.Claim("Find clips", evidence);
        return new HandoverSnapshot(List.of(new HandoverSnapshot.Item(task, owner, statusClaim, next, List.of())));
    }

    @Test
    void anonymousUsersCanSeeDemoButNotPrivateProjects() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Try the sample project")));
        mvc.perform(get("/projects/" + projectId)).andExpect(status().is3xxRedirection());
    }

    @Test
    void recipientSeesOnlyAnApprovedVersionAndCannotPerformCoordinatorActions() throws Exception {
        mvc.perform(get("/projects/" + projectId + "/versions/" + versionId).with(user("recipient")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("F2-L2")))
                .andExpect(content().string(containsString("Collect recordings,In progress")));
        mvc.perform(post("/projects/" + projectId + "/recipients").with(user("recipient")).with(csrf())
                        .param("recipientUsername", "stranger"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/projects/" + projectId + "/approve").with(user("recipient")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/projects/" + projectId + "/versions/" + versionId).with(user("stranger")))
                .andExpect(status().isNotFound());
    }

    @Test
    void coordinatorCanRevokeRecipientAccess() throws Exception {
        String removePath = "/projects/" + projectId + "/recipients/remove";
        mvc.perform(post(removePath).with(user("recipient")).with(csrf()).param("recipientUsername", "recipient"))
                .andExpect(status().isForbidden());
        mvc.perform(post(removePath).with(user("coordinator")).with(csrf()).param("recipientUsername", "recipient"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/projects/" + projectId + "/versions/" + versionId).with(user("recipient")))
                .andExpect(status().isNotFound());
    }

    @Test
    void coordinatorAddsTheSelectedRecipientAndRetriesAreSafe() throws Exception {
        var addPath = "/projects/" + projectId + "/recipients";
        mvc.perform(get("/projects/" + projectId).with(user("coordinator")))
                .andExpect(content().string(containsString("name=\"recipientUsername\"")));
        mvc.perform(post(addPath).with(user("coordinator")).with(csrf()).param("recipientUsername", "stranger"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Recipient access added."));
        var account = repository.accountByUsername("stranger").orElseThrow();
        assertEquals("RECIPIENT", repository.memberRole(projectId, account.id()).orElseThrow());
        mvc.perform(get("/projects/" + projectId + "/versions/" + versionId).with(user("stranger")))
                .andExpect(status().isOk());
        mvc.perform(post(addPath).with(user("coordinator")).with(csrf()).param("recipientUsername", "stranger"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Account 'stranger' already has recipient access to this project."));
    }

    @Test
    void publicDemoShowsConflictsAndKeepsEditsAfterValidationError() throws Exception {
        var review = mvc.perform(post("/demo").with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Possible conflicting status"))).andReturn();
        var session = (MockHttpSession) review.getRequest().getSession(false);
        var draft = (ReviewController.Draft) session.getAttribute("review:demo");
        String group = draft.groups().getFirst().id();

        mvc.perform(post("/demo/approve").session(session).with(csrf())
                        .param("requestToken", draft.token()).param("task_" + group, "Edited title")
                        .param("evidence_task_" + group, "F1-L1"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Select at least one item")))
                .andExpect(content().string(containsString("Edited title")));

        mvc.perform(post("/demo/approve").session(session).with(csrf())
                        .param("requestToken", draft.token()).param("approved", group)
                        .param("task_" + group, "Edited title").param("owner_" + group, "")
                        .param("status_" + group, "Unknown").param("next_" + group, "Find missing clips")
                        .param("evidence_task_" + group, "F1-L1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/demo/result"));
        mvc.perform(get("/demo/result").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Edited title")))
                .andExpect(content().string(containsString("F1-L1")));
    }

    @Test
    void coordinatorCanUploadReviewAndApproveEvidence() throws Exception {
        byte[] source = ("Task,Owner,Status,Next step\nCollect recordings,Rahul,In progress,Find clips\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = new MockMultipartFile("files", "tasks.csv", "text/csv", source);
        var review = mvc.perform(multipart("/projects/" + projectId + "/upload").file(file)
                        .with(user("coordinator")).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("F1-L2"))).andReturn();
        var session = (MockHttpSession) review.getRequest().getSession(false);
        var draft = (ReviewController.Draft) session.getAttribute("review:project:" + projectId);
        String group = draft.groups().getFirst().id();
        mvc.perform(post("/projects/" + projectId + "/approve").session(session).with(user("coordinator")).with(csrf())
                        .param("requestToken", draft.token()).param("approved", group)
                        .param("task_" + group, "Collect recordings").param("owner_" + group, "Rahul")
                        .param("status_" + group, "In progress").param("next_" + group, "Find clips")
                        .param("evidence_task_" + group, "F1-L2"))
                .andExpect(status().is3xxRedirection());
        var saved = repository.latestVersion(projectId).orElseThrow();
        assertEquals(2, saved.number());
        assertEquals("F1-L2", handovers.readSnapshot(saved.snapshotJson()).items().getFirst().task().evidence().getFirst().id());
    }

    @Test
    void duplicateApprovalsAndAcknowledgementsDoNotCreateDuplicates() throws Exception {
        long duplicate = handovers.approve(projectId, coordinator, requestToken, snapshot("In progress"));
        assertEquals(versionId, duplicate);
        assertEquals(1, repository.versions(projectId).size());
        var ackPath = "/projects/" + projectId + "/versions/" + versionId + "/acknowledge";
        mvc.perform(post(ackPath).with(user("recipient")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post(ackPath).with(user("recipient")).with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(1, repository.acknowledgements(versionId).size());
        mvc.perform(post(ackPath).with(user("recipient"))).andExpect(status().isForbidden());
    }

    @Test
    void storesSeparateVersionsAndTheirChanges() {
        long secondId = handovers.approve(projectId, coordinator, UUID.randomUUID().toString(), snapshot("Completed"));
        assertEquals(2, repository.versions(projectId).size());
        var second = repository.version(projectId, secondId).orElseThrow();
        assertTrue(second.changeSummary().contains("status or status evidence"));
        assertEquals("Completed", handovers.readSnapshot(second.snapshotJson()).items().getFirst().status().value());
        assertEquals("In progress", handovers.readSnapshot(repository.version(projectId, versionId).orElseThrow().snapshotJson())
                .items().getFirst().status().value());
    }

    @Test
    void registrationStoresOnlyAPasswordHash() throws Exception {
        String password = "strong-test-password";
        mvc.perform(post("/register").with(csrf()).param("username", "new-user")
                        .param("password", password).param("confirmPassword", password))
                .andExpect(status().is3xxRedirection());
        var account = repository.accountByUsername("new-user").orElseThrow();
        assertNotEquals(password, account.passwordHash());
        assertTrue(passwords.matches(password, account.passwordHash()));
        mvc.perform(post("/login").with(csrf()).param("username", "new-user").param("password", password))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/projects"));
    }
}
