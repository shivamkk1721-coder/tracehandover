package com.tracehandover.tracehandover;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceReconcilerTest {
    private EvidenceReconciler.Source source(String id, String file, String text) {
        return new EvidenceReconciler.Source(id, file, text);
    }

    @Test
    void groupsRelatedNoteAndCsvAndKeepsFieldLevelSources() {
        var groups = EvidenceReconciler.reconcile(List.of(
                source("F1-L1", "notes.txt", "The team still needs to collect the remaining recordings."),
                source("F2-L1", "tasks.csv", "Task,Owner,Status,Next step"),
                source("F2-L2", "tasks.csv", "Collect remaining recordings,Unassigned,In progress,Find missing clips")
        ), Map.of());

        assertEquals(1, groups.size());
        var group = groups.getFirst();
        assertEquals(List.of("F1-L1", "F2-L2"), group.sources().stream().map(EvidenceReconciler.Source::id).toList());
        assertEquals("In progress", group.claims().get("status").getFirst().value());
        assertEquals(List.of("F2-L2"), group.claims().get("status").getFirst().sources().stream().map(EvidenceReconciler.Source::id).toList());
        assertTrue(group.claims().get("owner").isEmpty());
        assertTrue(group.warnings().stream().anyMatch(w -> w.contains("No owner value")));
    }

    @Test
    void highlightsContradictoryKnownValuesButNotMissingValues() {
        var groups = EvidenceReconciler.reconcile(List.of(
                source("F1-L1", "a.csv", "Task,Owner,Status,Next step"),
                source("F1-L2", "a.csv", "Collect remaining recordings,Rahul,In progress,Find missing clips"),
                source("F2-L1", "b.csv", "Task,Owner,Status,Next step"),
                source("F2-L2", "b.csv", "Collect remaining recordings,Rahul,Completed,Find missing clips")
        ), Map.of());

        var group = groups.getFirst();
        assertEquals(1, groups.size());
        assertEquals(2, group.claims().get("status").size());
        assertTrue(group.warnings().stream().anyMatch(w -> w.contains("Possible conflicting status")));
        assertEquals(2, group.claims().get("owner").getFirst().sources().size());
    }

    @Test
    void keepsUnrelatedTasksSeparate() {
        var groups = EvidenceReconciler.reconcile(List.of(
                source("F1-L1", "notes.txt", "Review presentation recordings"),
                source("F1-L2", "notes.txt", "Confirm catering schedule")
        ), Map.of());
        assertEquals(2, groups.size());
    }

    @Test
    void letsReviewerKeepRelatedPassagesSeparate() {
        var sources = List.of(
                source("F1-L1", "notes.txt", "Collect remaining recordings"),
                source("F1-L2", "notes.txt", "Collect remaining recordings"));
        assertEquals(1, EvidenceReconciler.reconcile(sources, Map.of(), true).size());
        var separate = EvidenceReconciler.reconcile(sources, Map.of(), false);
        assertEquals(2, separate.size());
        assertEquals("F1-L2", separate.get(1).sources().getFirst().id());
    }

    @Test
    void parsesQuotedCsvWithoutTreatingUnknownSchemaAsKnown() {
        assertEquals(List.of("Review, then collect", "Rahul", "In progress", "Ask \"Kai\""),
                EvidenceReconciler.parseCsvLine("\"Review, then collect\",Rahul,In progress,\"Ask \"\"Kai\"\"\""));
        var groups = EvidenceReconciler.reconcile(List.of(
                source("F1-L1", "unknown.csv", "topic,person,phase,comment"),
                source("F1-L2", "unknown.csv", "Collect recordings,Asha,Completed,Send")
        ), Map.of());
        assertTrue(groups.stream().allMatch(g -> g.claims().get("owner").isEmpty()));
    }

    @Test
    void rejectsDuplicateSourceIdsAndIgnoresInventedIds() {
        var source = source("F1-L1", "notes.txt", "Review recording lengths");
        assertThrows(IllegalArgumentException.class,
                () -> EvidenceReconciler.reconcile(List.of(source, source), Map.of()));
        var groups = EvidenceReconciler.reconcile(List.of(source),
                Map.of("F99-L9", Map.of("task", "Invented task", "owner", "Invented owner")));
        assertEquals("Review recording lengths", groups.getFirst().title());
        assertTrue(groups.getFirst().claims().get("owner").isEmpty());
    }
}
