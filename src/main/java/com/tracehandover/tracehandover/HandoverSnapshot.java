package com.tracehandover.tracehandover;

import java.util.List;

/** Immutable approved content; evidence is chosen by the reviewer, not verified by the model. */
public record HandoverSnapshot(List<Item> items) {
    public HandoverSnapshot { items = List.copyOf(items); }

    public record SourceRef(String id, String file, String text) {}

    public record Claim(String value, List<SourceRef> evidence) {
        public Claim { evidence = List.copyOf(evidence); }
    }

    public record Item(Claim task, Claim owner, Claim status, Claim next, List<String> warnings) {
        public Item { warnings = List.copyOf(warnings); }
    }
}
