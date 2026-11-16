package hk.chris.proxy;

import java.time.LocalDate;
import java.util.List;

/** Export boundary for future Fcitx/Rime platform adapters; it never edits a live IME database. */
final class FcitxVocabularyAdapter {
    private FcitxVocabularyAdapter() {}

    static String toRimeYaml(List<VocabularyEntry> entries) {
        StringBuilder out = new StringBuilder();
        out.append("# Rime dictionary generated locally by Chris Proxy\n")
                .append("---\n")
                .append("name: chris_personal\n")
                .append("version: \"").append(LocalDate.now()).append("\"\n")
                .append("sort: by_weight\n")
                .append("use_preset_vocabulary: false\n")
                .append("...\n");
        for (VocabularyEntry entry : entries) {
            if (!entry.enabled) continue;
            out.append(entry.term).append('\t').append(entry.reading).append('\t').append(entry.weight).append('\n');
        }
        return out.toString();
    }

    static String toFcitxTsv(List<VocabularyEntry> entries) {
        StringBuilder out = new StringBuilder("# term\treading/code\tweight\n");
        for (VocabularyEntry entry : entries) {
            if (entry.enabled) out.append(entry.term).append('\t').append(entry.reading)
                    .append('\t').append(entry.weight).append('\n');
        }
        return out.toString();
    }
}
