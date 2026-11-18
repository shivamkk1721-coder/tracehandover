package com.tracehandover.tracehandover;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeSummaryTest {
    private HandoverSnapshot.Item item(String status, String sourceId) {
        var evidence = List.of(new HandoverSnapshot.SourceRef(sourceId, "tasks.csv", "source text"));
        return new HandoverSnapshot.Item(new HandoverSnapshot.Claim("Collect recordings", evidence),
                new HandoverSnapshot.Claim("Rahul", evidence), new HandoverSnapshot.Claim(status, evidence),
                new HandoverSnapshot.Claim("Review clip", evidence), List.of());
    }

    @Test
    void reportsFieldAndEvidenceChanges() {
        var before = new HandoverSnapshot(List.of(item("In progress", "F1-L1")));
        var after = new HandoverSnapshot(List.of(item("Completed", "F2-L2")));
        String result = ChangeSummary.describe(before, after);
        assertTrue(result.contains("status or status evidence"));
        assertTrue(result.contains("task or task evidence"));
        assertEquals("In progress", before.items().getFirst().status().value());
    }

    @Test
    void recordsAddedAndRemovedItemsAndUnchangedVersions() {
        var empty = new HandoverSnapshot(List.of());
        var one = new HandoverSnapshot(List.of(item("In progress", "F1-L1")));
        assertTrue(ChangeSummary.describe(empty, one).contains("Added item 1"));
        assertTrue(ChangeSummary.describe(one, empty).contains("Removed item 1"));
        assertEquals("No content changes from the previous version.", ChangeSummary.describe(one, one));
    }
}
