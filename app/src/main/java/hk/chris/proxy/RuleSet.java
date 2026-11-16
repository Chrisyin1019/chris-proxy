package hk.chris.proxy;

/**
 * A named domain/IP list referenced by one rule (Surge's RULE-SET). Lists that
 * came from a URL can be refreshed; pasted or file lists are static. The
 * entries themselves live in {@link RuleSetStore}'s files, not here.
 */
public final class RuleSet {
    public final String id;
    public final String name;
    /** Source URL, or "" when imported from text or a file. */
    public final String url;
    public final int count;
    public final long updatedAt;
    /** Last refresh failure, or "" when the last attempt succeeded. */
    public final String error;

    public RuleSet(String id, String name, String url, int count, long updatedAt, String error) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "未命名清單" : name.trim();
        this.url = url == null ? "" : url;
        this.count = count;
        this.updatedAt = updatedAt;
        this.error = error == null ? "" : error;
    }

    public boolean updatable() {
        return !url.isEmpty();
    }

    public boolean stale(long now) {
        return updatable() && now - updatedAt > 24L * 60 * 60 * 1000;
    }

    RuleSet withRefresh(int newCount, long time) {
        return new RuleSet(id, name, url, newCount, time, "");
    }

    RuleSet withError(String reason) {
        return new RuleSet(id, name, url, count, updatedAt, reason);
    }

    RuleSet withName(String newName) {
        return new RuleSet(id, newName, url, count, updatedAt, error);
    }
}
