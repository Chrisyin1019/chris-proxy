package hk.chris.proxy;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One local personal-vocabulary item, independent from any IME database format. */
public final class VocabularyEntry {
    public static final int MAX_TERM_LENGTH = 80;
    public static final int MAX_READING_LENGTH = 120;
    public static final int MAX_WEIGHT = 1_000_000;

    public final String id;
    public final String term;
    public final String reading;
    public final int weight;
    public final boolean enabled;

    public VocabularyEntry(String id, String term, String reading, int weight, boolean enabled) {
        String normalizedId = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!normalizedId.matches("[0-9a-f-]{12,64}")) throw new IllegalArgumentException("詞條 ID 格式錯誤");
        this.id = normalizedId;
        this.term = clean(term, MAX_TERM_LENGTH, "詞條");
        this.reading = cleanOptional(reading, MAX_READING_LENGTH, "讀音／編碼");
        if (weight < 1 || weight > MAX_WEIGHT) throw new IllegalArgumentException("權重需為 1～" + MAX_WEIGHT);
        this.weight = weight;
        this.enabled = enabled;
    }

    public static VocabularyEntry create(String term, String reading, int weight) {
        return new VocabularyEntry(UUID.randomUUID().toString(), term, reading, weight, true);
    }

    public VocabularyEntry edited(String term, String reading, int weight) {
        return new VocabularyEntry(id, term, reading, weight, enabled);
    }

    public VocabularyEntry withEnabled(boolean value) {
        return new VocabularyEntry(id, term, reading, weight, value);
    }

    String dedupeKey() {
        return term.toLowerCase(Locale.ROOT) + "\u0000" + reading.toLowerCase(Locale.ROOT);
    }

    private static String clean(String raw, int max, String label) {
        String value = cleanOptional(raw, max, label);
        if (value.isEmpty()) throw new IllegalArgumentException(label + "不可為空");
        return value;
    }

    private static String cleanOptional(String raw, int max, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > max) throw new IllegalArgumentException(label + "不可超過 " + max + " 字");
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + "不可包含換行或 Tab");
        }
        return value;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VocabularyEntry)) return false;
        VocabularyEntry entry = (VocabularyEntry) other;
        return id.equals(entry.id) && term.equals(entry.term) && reading.equals(entry.reading)
                && weight == entry.weight && enabled == entry.enabled;
    }

    @Override public int hashCode() {
        return Objects.hash(id, term, reading, weight, enabled);
    }
}
