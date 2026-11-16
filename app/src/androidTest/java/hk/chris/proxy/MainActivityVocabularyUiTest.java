package hk.chris.proxy;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.startsWith;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public final class MainActivityVocabularyUiTest {
    private ActivityScenario<MainActivity> scenario;

    @Before public void launchWithEmptyVocabulary() {
        Context context = ApplicationProvider.getApplicationContext();
        deleteRecursively(new File(context.getFilesDir(), "vocabulary"));
        scenario = ActivityScenario.launch(MainActivity.class);
    }

    @After public void closeActivity() {
        if (scenario != null) scenario.close();
    }

    @Test public void vocabularyDrawerEntryShowsEmptyState() {
        openVocabulary();
        onView(withText("還沒有詞條。可先新增常用術語，或貼上 Rime／TSV 詞庫。"))
                .check(matches(isDisplayed()));
        onView(withText("新增詞條")).check(matches(isDisplayed()));
    }

    @Test public void addedVocabularyEntrySurvivesActivityRecreation() {
        openVocabulary();
        onView(withText("新增詞條")).perform(click());
        onView(withHint("詞條，例如 CCTV盲咗")).perform(replaceText("CCTV盲咗"));
        onView(withHint("注音／Rime 編碼（可留空）")).perform(replaceText("cctv"));
        onView(withHint("權重 1～1000000")).perform(replaceText("700"));
        onView(withText("儲存")).perform(click());
        onView(withText("CCTV盲咗")).check(matches(isDisplayed()));

        scenario.recreate();
        openVocabulary();
        onView(withText("CCTV盲咗")).check(matches(isDisplayed()));
        onView(withText(startsWith("v2 · 啟用 1／全部 1"))).check(matches(isDisplayed()));
    }

    private static void openVocabulary() {
        onView(withContentDescription("開啟選單")).perform(click());
        onView(withContentDescription(startsWith("個人詞庫（"))).perform(click());
        onView(withText("個人詞庫")).check(matches(isDisplayed()));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new AssertionError("無法清除測試詞庫：" + file);
    }
}
