package hk.chris.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure presentation model so vocabulary screen states can be tested without a device. */
final class VocabularyUiModel {
    private VocabularyUiModel() {}

    static int enabledCount(List<VocabularyEntry> entries) {
        int result = 0;
        for (VocabularyEntry entry : entries) if (entry.enabled) result++;
        return result;
    }

    static List<VocabularyEntry> filter(List<VocabularyEntry> entries, String query) {
        String value = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return new ArrayList<>(entries);
        ArrayList<VocabularyEntry> result = new ArrayList<>();
        for (VocabularyEntry entry : entries) {
            if (entry.term.toLowerCase(Locale.ROOT).contains(value)
                    || entry.reading.toLowerCase(Locale.ROOT).contains(value)) result.add(entry);
        }
        return result;
    }

    static String detail(VocabularyEntry entry) {
        return (entry.enabled ? "" : "停用 · ")
                + (entry.reading.isEmpty() ? "無讀音／編碼" : entry.reading)
                + " · 權重 " + entry.weight;
    }
}
