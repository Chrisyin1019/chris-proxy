package hk.chris.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A deliberately small, local domain-list plugin. It does not decrypt HTTPS,
 * install a CA, or act as MITM. The VPN core can call blockedDomains() for DNS
 * and host-level decisions once a real packet engine is connected.
 */
public final class AdBlockPlugin {
    private AdBlockPlugin() {}

    public static Set<String> parse(Reader reader) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        BufferedReader buffered = new BufferedReader(reader);
        String line;
        while ((line = buffered.readLine()) != null) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("!") || item.startsWith("#")) continue;
            if (item.startsWith("||")) item = item.substring(2);
            if (item.startsWith("@@")) continue;
            int caret = item.indexOf('^');
            if (caret >= 0) item = item.substring(0, caret);
            if (item.contains(" ")) {
                String[] parts = item.split("\\s+");
                item = parts.length > 1 ? parts[parts.length - 1] : item;
            }
            item = Policy.normalize(item);
            if (!item.isEmpty() && item.contains(".")) result.add(item);
        }
        return result;
    }
}
