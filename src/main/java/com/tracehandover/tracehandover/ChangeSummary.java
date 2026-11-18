package com.tracehandover.tracehandover;

import java.util.ArrayList;
import java.util.List;

/** Describes differences between immutable snapshots by their displayed item order. */
public final class ChangeSummary {
    private ChangeSummary() {}

    public static String describe(HandoverSnapshot before, HandoverSnapshot after) {
        List<String> changes = new ArrayList<>();
        int max = Math.max(before.items().size(), after.items().size());
        for (int i = 0; i < max; i++) {
            if (i >= before.items().size()) { changes.add("Added item " + (i + 1)); continue; }
            if (i >= after.items().size()) { changes.add("Removed item " + (i + 1)); continue; }
            var oldItem = before.items().get(i);
            var newItem = after.items().get(i);
            List<String> fields = new ArrayList<>();
            if (!oldItem.task().equals(newItem.task())) fields.add("task or task evidence");
            if (!oldItem.owner().equals(newItem.owner())) fields.add("owner or owner evidence");
            if (!oldItem.status().equals(newItem.status())) fields.add("status or status evidence");
            if (!oldItem.next().equals(newItem.next())) fields.add("next action or evidence");
            if (!oldItem.warnings().equals(newItem.warnings())) fields.add("review notes");
            if (!fields.isEmpty()) changes.add("Item " + (i + 1) + ": " + String.join(", ", fields));
        }
        return changes.isEmpty() ? "No content changes from the previous version." : String.join("; ", changes);
    }
}
