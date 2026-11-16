package hk.chris.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Parses simple TSV/Rime dictionary rows and merges them without logging term text. */
final class VocabularyImporter {
    static final int MAX_INPUT_CHARS = 2 * 1024 * 1024;

    private VocabularyImporter() {}

    static Result merge(List<VocabularyEntry> existing, String raw) {
        String text = raw == null ? "" : raw;
        if (text.length() > MAX_INPUT_CHARS) throw new IllegalArgumentException("匯入內容超過 2 MB");
        LinkedHashMap<String, VocabularyEntry> merged = new LinkedHashMap<>();
        for (VocabularyEntry entry : existing) merged.put(entry.dedupeKey(), entry);
        int added = 0;
        int updated = 0;
        int skipped = 0;
        for (String sourceLine : text.split("\\r?\\n")) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.equals("---") || line.equals("...")) continue;
            if (!line.contains("\t") && line.matches("(?i)(name|version|sort|columns|use_preset_vocabulary)\\s*:.*")) continue;
            String[] columns = sourceLine.split("\t", -1);
            if (columns.length > 3) { skipped++; continue; }
            String term = columns[0].trim();
            String reading = "";
            int weight = 100;
            try {
                if (columns.length == 2) {
                    String second = columns[1].trim();
                    if (second.matches("\\d+")) weight = Integer.parseInt(second); else reading = second;
                } else if (columns.length == 3) {
                    reading = columns[1].trim();
                    if (!columns[2].trim().isEmpty()) weight = Integer.parseInt(columns[2].trim());
                }
                VocabularyEntry draft = VocabularyEntry.create(term, reading, weight);
                VocabularyEntry previous = merged.get(draft.dedupeKey());
                if (previous == null) {
                    if (merged.size() >= VocabularyRepository.MAX_ENTRIES) { skipped++; continue; }
                    merged.put(draft.dedupeKey(), draft);
                    added++;
                } else if (previous.weight != weight) {
                    merged.put(previous.dedupeKey(), new VocabularyEntry(previous.id, previous.term,
                            previous.reading, weight, previous.enabled));
                    updated++;
                }
            } catch (Exception e) {
                skipped++;
            }
        }
        return new Result(new ArrayList<>(merged.values()), added, updated, skipped);
    }

    static final class Result {
        final List<VocabularyEntry> entries;
        final int added;
        final int updated;
        final int skipped;

        Result(List<VocabularyEntry> entries, int added, int updated, int skipped) {
            this.entries = entries;
            this.added = added;
            this.updated = updated;
            this.skipped = skipped;
        }
    }
}
