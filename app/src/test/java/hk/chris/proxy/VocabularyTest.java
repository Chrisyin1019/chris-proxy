package hk.chris.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VocabularyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void entryValidationRejectsUnsafeOrInvalidRows() {
        VocabularyEntry entry = new VocabularyEntry("abcdef123456", "CCTV盲咗", "cctv", 500, true);
        assertEquals("CCTV盲咗", entry.term);
        assertEquals("cctv", entry.reading);
        assertThrows(IllegalArgumentException.class,
                () -> VocabularyEntry.create("bad\tterm", "", 100));
        assertThrows(IllegalArgumentException.class,
                () -> VocabularyEntry.create("term", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> VocabularyEntry.create("", "", 100));
    }

    @Test public void importerUnderstandsPlainAndRimeRowsWithoutDuplicating() {
        VocabularyEntry existing = new VocabularyEntry("abcdef123456", "呢個mon爆咗", "mon", 50, false);
        String source = "---\nname: personal\n...\n"
                + "呢個mon爆咗\tmon\t900\n"
                + "CCTV盲咗\tcctv\t700\n"
                + "taxi!我嘅八達通爆咗\n"
                + "bad\trow\tweight\textra\n";
        VocabularyImporter.Result result = VocabularyImporter.merge(Arrays.asList(existing), source);
        assertEquals(2, result.added);
        assertEquals(1, result.updated);
        assertEquals(1, result.skipped);
        assertEquals(3, result.entries.size());
        assertEquals(900, result.entries.get(0).weight);
        assertFalse(result.entries.get(0).enabled); // an import never silently re-enables a disabled item
    }

    @Test public void codecRoundTripPreservesOrderAndState() {
        List<VocabularyEntry> entries = Arrays.asList(
                new VocabularyEntry("abcdef123456", "mon", "mon", 400, true),
                new VocabularyEntry("abcdef123457", "CCTV", "cctv", 300, false));
        VocabularyCodec.Snapshot original = new VocabularyCodec.Snapshot(7, 1234, "測試", entries);
        VocabularyCodec.Snapshot decoded = VocabularyCodec.decode(VocabularyCodec.encode(original));
        assertEquals(7, decoded.version);
        assertEquals("測試", decoded.reason);
        assertEquals(entries, decoded.entries);
    }

    @Test public void repositoryPersistsVersionsAndRollsBackWithoutLosingCurrent() throws Exception {
        File root = temporary.newFolder("vocabulary");
        VocabularyRepository repository = new VocabularyRepository(root);
        VocabularyEntry a = new VocabularyEntry("abcdef123456", "mon", "mon", 100, true);
        VocabularyEntry b = new VocabularyEntry("abcdef123457", "CCTV", "cctv", 200, true);

        VocabularyCodec.Snapshot v2 = repository.save(Arrays.asList(a), "新增 A");
        VocabularyCodec.Snapshot v3 = repository.save(Arrays.asList(a, b), "新增 B");
        assertEquals(2, v2.version);
        assertEquals(3, v3.version);
        assertEquals(2, repository.history().size()); // includes the initial empty v1

        VocabularyCodec.Snapshot restored = repository.rollback(2);
        assertEquals(4, restored.version);
        assertEquals(1, restored.entries.size());
        assertEquals("mon", restored.entries.get(0).term);
        assertTrue(repository.history().stream().anyMatch(item -> item.version == 3 && item.entries.size() == 2));

        VocabularyRepository reopened = new VocabularyRepository(root);
        assertEquals(restored.entries, reopened.load().entries);
    }

    @Test public void repositoryBoundsHistoryAndRejectsDuplicates() throws Exception {
        VocabularyRepository repository = new VocabularyRepository(temporary.newFolder("bounded"));
        ArrayList<VocabularyEntry> entries = new ArrayList<>();
        for (int i = 0; i < VocabularyRepository.MAX_HISTORY + 4; i++) {
            entries.add(new VocabularyEntry(String.format("abcdef%06x", i), "term" + i, "", 100, true));
            repository.save(new ArrayList<>(entries), "step " + i);
        }
        assertEquals(VocabularyRepository.MAX_HISTORY, repository.history().size());
        VocabularyEntry duplicate = new VocabularyEntry("fedcba123456", "term0", "", 200, true);
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(Arrays.asList(entries.get(0), duplicate), "duplicate"));
    }

    @Test public void adaptersExportOnlyEnabledEntries() {
        List<VocabularyEntry> entries = Arrays.asList(
                new VocabularyEntry("abcdef123456", "CCTV盲咗", "cctv", 700, true),
                new VocabularyEntry("abcdef123457", "不要匯出", "no", 900, false));
        String rime = FcitxVocabularyAdapter.toRimeYaml(entries);
        String fcitx = FcitxVocabularyAdapter.toFcitxTsv(entries);
        assertTrue(rime.contains("CCTV盲咗\tcctv\t700"));
        assertTrue(fcitx.contains("CCTV盲咗\tcctv\t700"));
        assertFalse(rime.contains("不要匯出"));
        assertFalse(fcitx.contains("不要匯出"));
    }

    @Test public void uiModelCoversEmptySearchMixedAndDisabledStates() {
        List<VocabularyEntry> entries = Arrays.asList(
                new VocabularyEntry("abcdef123456", "呢個mon爆咗", "mon", 500, true),
                new VocabularyEntry("abcdef123457", "CCTV盲咗", "cctv", 300, false));
        assertEquals(1, VocabularyUiModel.enabledCount(entries));
        assertEquals(2, VocabularyUiModel.filter(entries, "").size());
        assertEquals("CCTV盲咗", VocabularyUiModel.filter(entries, "CCTV").get(0).term);
        assertEquals("呢個mon爆咗", VocabularyUiModel.filter(entries, "mon").get(0).term);
        assertTrue(VocabularyUiModel.detail(entries.get(1)).startsWith("停用"));
        assertTrue(VocabularyUiModel.filter(entries, "missing").isEmpty());
    }
}
