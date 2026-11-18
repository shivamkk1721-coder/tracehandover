package com.tracehandover.tracehandover;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Groups likely-related source passages and highlights values that require review.
 * A cited passage is not automatically a verified claim: the reviewer decides support.
 */
public final class EvidenceReconciler {
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "before", "by", "each", "for",
            "from", "had", "has", "have", "in", "is", "it", "its", "of", "on", "or",
            "some", "still", "the", "their", "then", "there", "they", "this", "to", "was",
            "were", "will", "with", "we", "our", "team", "need", "needs", "asked", "professor");
    private static final Set<String> UNKNOWN_VALUES = Set.of(
            "", "unknown", "not specified", "unassigned", "n/a", "none", "not provided");
    private static final List<String> FIELDS = List.of("task", "owner", "status", "next");

    public record Source(String id, String file, String text) {
        public Source {
            Objects.requireNonNull(id, "source id");
            Objects.requireNonNull(file, "source file");
            Objects.requireNonNull(text, "source text");
            if (id.isBlank()) throw new IllegalArgumentException("Source ID must not be blank");
        }
    }

    public record Claim(String value, List<Source> sources) {
        public Claim { sources = List.copyOf(sources); }
    }

    public record Group(String id, String title, List<Source> sources,
                        Map<String, List<Claim>> claims, List<String> warnings) {
        public Group {
            sources = List.copyOf(sources);
            Map<String, List<Claim>> safe = new LinkedHashMap<>();
            claims.forEach((key, value) -> safe.put(key, List.copyOf(value)));
            claims = Collections.unmodifiableMap(safe);
            warnings = List.copyOf(warnings);
        }
    }

    private EvidenceReconciler() {}

    /** Suggestions are keyed by a source ID; unknown IDs cannot introduce evidence. */
    public static List<Group> reconcile(List<Source> sources,
                                        Map<String, Map<String, String>> suggestions) {
        return reconcile(sources, suggestions, true);
    }

    /** Reviewers can also choose to keep every passage separate. */
    public static List<Group> reconcile(List<Source> sources,
                                        Map<String, Map<String, String>> suggestions,
                                        boolean groupRelated) {
        Objects.requireNonNull(sources, "sources");
        Map<String, Map<String, String>> safeSuggestions = suggestions == null ? Map.of() : suggestions;
        Set<String> seen = new LinkedHashSet<>();
        Set<String> recognizedCsvSources = new LinkedHashSet<>();
        for (Source source : sources) {
            if (isCsvHeader(source)) recognizedCsvSources.add(fileId(source.id()));
        }
        List<MutableGroup> groups = new ArrayList<>();

        for (Source source : sources) {
            if (!seen.add(source.id())) throw new IllegalArgumentException("Duplicate source ID");
            if (source.text().isBlank() || isCsvHeader(source)) continue;
            Map<String, String> values = valuesFor(source, safeSuggestions.get(source.id()),
                    recognizedCsvSources.contains(fileId(source.id())));
            String title = values.get("task");
            Set<String> tokens = tokens(title);
            MutableGroup match = null;
            if (groupRelated) {
                for (MutableGroup candidate : groups) {
                    if (related(tokens, candidate.titleTokens)) {
                        match = candidate;
                        break;
                    }
                }
            }
            if (match == null) {
                match = new MutableGroup("G" + (groups.size() + 1), title, tokens);
                groups.add(match);
            }
            match.add(source, values);
        }
        return groups.stream().map(MutableGroup::freeze).toList();
    }

    private static boolean isCsvHeader(Source source) {
        if (!source.file().toLowerCase(Locale.ROOT).endsWith(".csv")) return false;
        List<String> fields = parseCsvLine(source.text());
        return fields.size() >= 4 && fields.get(0).strip().equalsIgnoreCase("task")
                && fields.get(1).strip().equalsIgnoreCase("owner")
                && fields.get(2).strip().equalsIgnoreCase("status")
                && Set.of("next step", "next action", "next").contains(fields.get(3).strip().toLowerCase(Locale.ROOT));
    }

    private static String fileId(String sourceId) {
        int separator = sourceId.indexOf('-');
        return separator < 0 ? sourceId : sourceId.substring(0, separator);
    }

    private static Map<String, String> valuesFor(Source source, Map<String, String> suggestion,
                                                  boolean recognizedCsv) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> csv = recognizedCsv && source.file().toLowerCase(Locale.ROOT).endsWith(".csv")
                ? parseCsvLine(source.text()) : List.of();
        String defaultTask = csv.size() >= 4 ? csv.get(0) : source.text();
        values.put("task", choose(suggestion, "task", defaultTask));
        values.put("owner", choose(suggestion, "owner", csv.size() >= 4 ? csv.get(1) : ""));
        values.put("status", choose(suggestion, "status", csv.size() >= 4 ? csv.get(2) : ""));
        values.put("next", choose(suggestion, "next", csv.size() >= 4 ? csv.get(3) : ""));
        values.put("warning", choose(suggestion, "warning", ""));
        return values;
    }

    private static String choose(Map<String, String> suggestion, String field, String fallback) {
        if (suggestion == null || !suggestion.containsKey(field)) return clean(fallback);
        String value = clean(suggestion.get(field));
        return !known(value) && known(fallback) ? clean(fallback) : value;
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }

    /** Handles quotes and escaped quotes within one source line; multiline CSV is not supported. */
    static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                result.add(value.toString().strip());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        if (quoted) return List.of(line);
        result.add(value.toString().strip());
        return result;
    }

    private static Set<String> tokens(String text) {
        String normal = Normalizer.normalize(clean(text), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        Set<String> words = new LinkedHashSet<>();
        for (String token : normal.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) continue;
            String stem = token;
            if (stem.length() > 6 && stem.endsWith("ing")) stem = stem.substring(0, stem.length() - 3);
            else if (stem.length() > 5 && stem.endsWith("ed")) stem = stem.substring(0, stem.length() - 2);
            else if (stem.length() > 4 && stem.endsWith("s")) stem = stem.substring(0, stem.length() - 1);
            if (!STOP_WORDS.contains(stem)) words.add(stem);
        }
        return words;
    }

    private static boolean related(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) return false;
        if (first.equals(second)) return true;
        Set<String> common = new LinkedHashSet<>(first);
        common.retainAll(second);
        if (common.size() < 2) return false;
        // Similarity is a suggestion, not proof. The user must inspect grouped passages.
        return (double) common.size() / Math.min(first.size(), second.size()) >= 0.75;
    }

    private static boolean known(String value) {
        return !UNKNOWN_VALUES.contains(clean(value).toLowerCase(Locale.ROOT));
    }

    private static final class MutableGroup {
        private final String id;
        private final String title;
        private final Set<String> titleTokens;
        private final List<Source> sources = new ArrayList<>();
        private final Map<String, Map<String, MutableClaim>> claims = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        MutableGroup(String id, String title, Set<String> titleTokens) {
            this.id = id;
            this.title = title;
            this.titleTokens = titleTokens;
            FIELDS.forEach(field -> claims.put(field, new LinkedHashMap<>()));
        }

        void add(Source source, Map<String, String> values) {
            sources.add(source);
            for (String field : FIELDS) {
                String value = clean(values.get(field));
                if (!known(value)) continue;
                String key = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                MutableClaim claim = claims.get(field).computeIfAbsent(key, k -> new MutableClaim(value));
                claim.sources.add(source);
            }
            String warning = clean(values.get("warning"));
            if (!warning.isBlank()) warnings.add(source.id() + ": " + warning);
        }

        Group freeze() {
            Map<String, List<Claim>> frozen = new LinkedHashMap<>();
            claims.forEach((field, values) -> frozen.put(field,
                    values.values().stream().map(c -> new Claim(c.value, c.sources)).toList()));
            for (String field : List.of("owner", "status", "next")) {
                if (frozen.get(field).size() > 1)
                    warnings.add("Possible conflicting " + field + " values. Compare the sources and timing.");
                else if (frozen.get(field).isEmpty())
                    warnings.add("No " + field + " value was identified; review the source manually.");
            }
            return new Group(id, title, sources, frozen, warnings);
        }
    }

    private static final class MutableClaim {
        private final String value;
        private final List<Source> sources = new ArrayList<>();
        MutableClaim(String value) { this.value = value; }
    }
}
