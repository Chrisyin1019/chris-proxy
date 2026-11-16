package hk.chris.proxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule set metadata in SharedPreferences, entries in files/rulesets/&lt;id&gt;.list
 * written in classical "DOMAIN-SUFFIX,example.com" lines so they re-parse
 * with {@link RuleListParser}.
 */
public final class RuleSetStore {
    public static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final String PREFS = "rule_sets";
    private static final String KEY = "sets";

    private RuleSetStore() {}

    public static synchronized List<RuleSet> list(Context context) {
        ArrayList<RuleSet> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                result.add(new RuleSet(o.getString("id"), o.optString("name"), o.optString("url"),
                        o.optInt("count"), o.optLong("updatedAt"), o.optString("error")));
            }
        } catch (Exception e) {
            AppLogger.log(context, "WARN", "規則集清單無法讀取=" + e.getClass().getSimpleName());
        }
        return result;
    }

    public static RuleSet find(Context context, String id) {
        for (RuleSet set : list(context)) if (set.id.equals(id)) return set;
        return null;
    }

    /** Creates a rule set from already-parsed entries. */
    public static synchronized RuleSet create(Context context, String name, String url, RuleListParser.Parsed parsed) throws IOException {
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        StringBuilder id = new StringBuilder();
        for (byte b : random) id.append(String.format(java.util.Locale.ROOT, "%02x", b));
        RuleSet set = new RuleSet(id.toString(), name, url, parsed.size(), System.currentTimeMillis(), "");
        writeEntries(context, set.id, parsed);
        List<RuleSet> sets = list(context);
        sets.add(set);
        saveAll(context, sets);
        return set;
    }

    public static synchronized void update(Context context, RuleSet set) {
        List<RuleSet> sets = list(context);
        for (int i = 0; i < sets.size(); i++) if (sets.get(i).id.equals(set.id)) sets.set(i, set);
        saveAll(context, sets);
    }

    public static synchronized void delete(Context context, String id) {
        List<RuleSet> sets = list(context);
        sets.removeIf(set -> set.id.equals(id));
        saveAll(context, sets);
        //noinspection ResultOfMethodCallIgnored
        file(context, id).delete();
    }

    /** The stored entries in classical form, or "" if missing. */
    public static String readRaw(Context context, String id) {
        File file = file(context, id);
        if (!file.exists()) return "";
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Inserts or replaces a set with a known id (configuration transfer keeps
     * ids so imported rules still point at their sets). Empty entries with a
     * URL leave the set stale, so it downloads on the next refresh.
     */
    public static synchronized void restore(Context context, RuleSet set, String rawEntries) throws IOException {
        RuleListParser.Parsed parsed = RuleListParser.parse(rawEntries == null ? "" : rawEntries);
        writeEntries(context, set.id, parsed);
        RuleSet stored = new RuleSet(set.id, set.name, set.url, parsed.size(),
                parsed.size() == 0 ? 0 : set.updatedAt, "");
        List<RuleSet> sets = list(context);
        sets.removeIf(existing -> existing.id.equals(set.id));
        sets.add(stored);
        saveAll(context, sets);
    }

    public static synchronized void deleteAll(Context context) {
        for (RuleSet set : list(context)) {
            //noinspection ResultOfMethodCallIgnored
            file(context, set.id).delete();
        }
        saveAll(context, new ArrayList<>());
    }

    /** All entries keyed by rule set id, for config generation. */
    public static Map<String, RuleListParser.Parsed> loadEntries(Context context) {
        LinkedHashMap<String, RuleListParser.Parsed> result = new LinkedHashMap<>();
        for (RuleSet set : list(context)) {
            File file = file(context, set.id);
            if (!file.exists()) continue;
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                result.put(set.id, RuleListParser.parse(reader));
            } catch (IOException e) {
                AppLogger.log(context, "WARN", "規則集讀取失敗=" + set.id);
            }
        }
        return result;
    }

    /** Downloads and re-parses an updatable set. Blocking; call off the UI thread. */
    public static RuleSet refresh(Context context, RuleSet set) {
        try {
            RuleListParser.Parsed parsed = RuleListParser.parse(download(set.url));
            if (parsed.size() == 0) throw new IOException("清單沒有可用項目");
            writeEntries(context, set.id, parsed);
            RuleSet updated = set.withRefresh(parsed.size(), System.currentTimeMillis());
            update(context, updated);
            AppLogger.log(context, "INFO", "規則集已更新=" + set.id + " 條數=" + parsed.size());
            return updated;
        } catch (Exception e) {
            String reason = e instanceof IOException && e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            RuleSet failed = set.withError(reason);
            update(context, failed);
            AppLogger.log(context, "WARN", "規則集更新失敗=" + set.id + " " + reason);
            return failed;
        }
    }

    /** Fetches a list over http(s) with size and time limits. */
    public static String download(String rawUrl) throws IOException {
        return new String(downloadBytes(rawUrl), StandardCharsets.UTF_8);
    }

    /** Raw bytes, for binary payloads such as sealed config files. */
    public static byte[] downloadBytes(String rawUrl) throws IOException {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (!(value.startsWith("https://") || value.startsWith("http://"))) {
            throw new IOException("網址必須以 http:// 或 https:// 開頭");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "ChrisProxy");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > MAX_DOWNLOAD_BYTES) throw new IOException("清單超過 8 MB");
                    bytes.write(buffer, 0, n);
                }
            }
            return bytes.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static void writeEntries(Context context, String id, RuleListParser.Parsed parsed) throws IOException {
        File target = file(context, id);
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("無法建立規則集資料夾");
        File temp = new File(dir, id + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            for (String v : parsed.suffixes) writer.write("DOMAIN-SUFFIX," + v + "\n");
            for (String v : parsed.domains) writer.write("DOMAIN," + v + "\n");
            for (String v : parsed.keywords) writer.write("DOMAIN-KEYWORD," + v + "\n");
            for (String v : parsed.cidrs) writer.write("IP-CIDR," + v + "\n");
        }
        if (!temp.renameTo(target)) throw new IOException("無法保存規則集");
    }

    private static void saveAll(Context context, List<RuleSet> sets) {
        JSONArray array = new JSONArray();
        try {
            for (RuleSet set : sets) {
                array.put(new JSONObject().put("id", set.id).put("name", set.name).put("url", set.url)
                        .put("count", set.count).put("updatedAt", set.updatedAt).put("error", set.error));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply();
    }

    private static File file(Context context, String id) {
        return new File(new File(context.getFilesDir(), "rulesets"), id + ".list");
    }
}
