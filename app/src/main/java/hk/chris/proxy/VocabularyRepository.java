package hk.chris.proxy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Atomic file-backed vocabulary repository with bounded local rollback history. */
final class VocabularyRepository {
    static final int MAX_ENTRIES = 5_000;
    static final int MAX_HISTORY = 10;

    private final File root;
    private final File current;
    private final File historyDirectory;

    VocabularyRepository(File root) {
        this.root = root;
        this.current = new File(root, "current.json");
        this.historyDirectory = new File(root, "history");
    }

    synchronized VocabularyCodec.Snapshot load() {
        if (!current.exists()) return new VocabularyCodec.Snapshot(1L, 0L, "初始版本", new ArrayList<>());
        try {
            return VocabularyCodec.decode(readUtf8(current));
        } catch (Exception e) {
            throw new IllegalStateException("本機詞庫無法讀取", e);
        }
    }

    synchronized VocabularyCodec.Snapshot save(List<VocabularyEntry> entries, String reason) {
        validate(entries);
        VocabularyCodec.Snapshot before = load();
        VocabularyCodec.Snapshot candidate = new VocabularyCodec.Snapshot(before.version + 1,
                System.currentTimeMillis(), cleanReason(reason), entries);
        if (sameEntries(before.entries, candidate.entries)) return before;
        writeSnapshot(historyFile(before), new VocabularyCodec.Snapshot(
                before.version, System.currentTimeMillis(), "變更前：" + cleanReason(reason), before.entries));
        writeSnapshot(current, candidate);
        trimHistory();
        return candidate;
    }

    synchronized List<VocabularyCodec.Snapshot> history() {
        ArrayList<VocabularyCodec.Snapshot> result = new ArrayList<>();
        File[] files = historyDirectory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try {
                    result.add(VocabularyCodec.decode(readUtf8(file)));
                } catch (Exception ignored) {
                    // History corruption must not block the active vocabulary.
                }
            }
        }
        result.sort(Comparator.comparingLong((VocabularyCodec.Snapshot value) -> value.version).reversed());
        return result;
    }

    synchronized VocabularyCodec.Snapshot rollback(long version) {
        VocabularyCodec.Snapshot target = null;
        for (VocabularyCodec.Snapshot snapshot : history()) if (snapshot.version == version) { target = snapshot; break; }
        if (target == null) throw new IllegalArgumentException("找不到詞庫版本 v" + version);
        VocabularyCodec.Snapshot before = load();
        if (current.exists()) writeSnapshot(historyFile(before), new VocabularyCodec.Snapshot(before.version,
                System.currentTimeMillis(), "回退前自動快照", before.entries));
        VocabularyCodec.Snapshot restored = new VocabularyCodec.Snapshot(before.version + 1,
                System.currentTimeMillis(), "回退自 v" + version, target.entries);
        writeSnapshot(current, restored);
        trimHistory();
        return restored;
    }

    private void writeSnapshot(File target, VocabularyCodec.Snapshot snapshot) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("無法建立詞庫資料夾");
            File temp = new File(parent, target.getName() + ".tmp");
            Files.write(temp.toPath(), VocabularyCodec.encode(snapshot).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("無法寫入本機詞庫", e);
        }
    }

    private File historyFile(VocabularyCodec.Snapshot snapshot) {
        return new File(historyDirectory, String.format(java.util.Locale.ROOT, "v%020d.json", snapshot.version));
    }

    private static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void trimHistory() {
        List<VocabularyCodec.Snapshot> snapshots = history();
        for (int i = MAX_HISTORY; i < snapshots.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            historyFile(snapshots.get(i)).delete();
        }
    }

    private static void validate(List<VocabularyEntry> entries) {
        if (entries == null) throw new IllegalArgumentException("缺少詞庫內容");
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("詞庫最多 " + MAX_ENTRIES + " 條");
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (VocabularyEntry entry : entries) {
            if (entry == null || !keys.add(entry.dedupeKey())) throw new IllegalArgumentException("詞庫含有重複詞條");
        }
    }

    private static boolean sameEntries(List<VocabularyEntry> left, List<VocabularyEntry> right) {
        return left.equals(right);
    }

    private static String cleanReason(String reason) {
        String value = reason == null ? "詞庫變更" : reason.trim();
        return value.isEmpty() ? "詞庫變更" : value.substring(0, Math.min(80, value.length()));
    }
}
