package hk.chris.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Versioned, local-only JSON format. It contains vocabulary metadata, never proxy secrets. */
final class VocabularyCodec {
    static final int FORMAT_VERSION = 1;

    private VocabularyCodec() {}

    static String encode(Snapshot snapshot) {
        try {
            JSONArray entries = new JSONArray();
            for (VocabularyEntry entry : snapshot.entries) {
                entries.put(new JSONObject()
                        .put("id", entry.id)
                        .put("term", entry.term)
                        .put("reading", entry.reading)
                        .put("weight", entry.weight)
                        .put("enabled", entry.enabled));
            }
            return new JSONObject()
                    .put("format", "chris-personal-vocabulary")
                    .put("formatVersion", FORMAT_VERSION)
                    .put("version", snapshot.version)
                    .put("savedAt", snapshot.savedAt)
                    .put("reason", snapshot.reason)
                    .put("entries", entries)
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("無法保存詞庫", e);
        }
    }

    static Snapshot decode(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            if (!"chris-personal-vocabulary".equals(root.optString("format"))) {
                throw new IllegalArgumentException("不是個人詞庫檔案");
            }
            ArrayList<VocabularyEntry> entries = new ArrayList<>();
            JSONArray array = root.optJSONArray("entries");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    try {
                        entries.add(new VocabularyEntry(item.getString("id"), item.getString("term"),
                                item.optString("reading"), item.optInt("weight", 100),
                                item.optBoolean("enabled", true)));
                    } catch (Exception ignored) {
                        // One malformed future item must not make the complete local backup unusable.
                    }
                }
            }
            return new Snapshot(Math.max(1L, root.optLong("version", 1L)),
                    root.optLong("savedAt"), root.optString("reason"), entries);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("詞庫格式錯誤", e);
        }
    }

    static final class Snapshot {
        final long version;
        final long savedAt;
        final String reason;
        final List<VocabularyEntry> entries;

        Snapshot(long version, long savedAt, String reason, List<VocabularyEntry> entries) {
            this.version = version;
            this.savedAt = savedAt;
            this.reason = reason == null ? "" : reason;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }
}
