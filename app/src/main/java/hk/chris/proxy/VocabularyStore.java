package hk.chris.proxy;

import android.content.Context;

import java.io.File;
import java.util.List;

/** Android boundary for the platform-neutral file repository. */
final class VocabularyStore {
    private static final Object LOCK = new Object();

    private VocabularyStore() {}

    private static VocabularyRepository repository(Context context) {
        return new VocabularyRepository(new File(context.getFilesDir(), "vocabulary"));
    }

    static VocabularyCodec.Snapshot load(Context context) {
        synchronized (LOCK) { return repository(context).load(); }
    }

    static VocabularyCodec.Snapshot save(Context context, List<VocabularyEntry> entries, String reason) {
        synchronized (LOCK) { return repository(context).save(entries, reason); }
    }

    static List<VocabularyCodec.Snapshot> history(Context context) {
        synchronized (LOCK) { return repository(context).history(); }
    }

    static VocabularyCodec.Snapshot rollback(Context context, long version) {
        synchronized (LOCK) { return repository(context).rollback(version); }
    }
}
