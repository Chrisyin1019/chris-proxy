package hk.chris.proxy;

import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.StringIterator;

import java.util.Iterator;
import java.util.List;

final class LibboxIterators {
    private LibboxIterators() {}

    static StringIterator strings(List<String> values) {
        return new StringIterator() {
            private final Iterator<String> iterator = values.iterator();
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public int len() { return values.size(); }
            @Override public String next() { return iterator.next(); }
        };
    }

    static NetworkInterfaceIterator networkInterfaces(List<NetworkInterface> values) {
        return new NetworkInterfaceIterator() {
            private final Iterator<NetworkInterface> iterator = values.iterator();
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public NetworkInterface next() { return iterator.next(); }
        };
    }
}
