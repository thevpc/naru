package net.thevpc.naru.ext.store.file;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.naru.impl.store.NaruFileDataStoreProvider;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NaruFileDataStoreTest {
    @Test
    void recordsAreDurableAndTransactionsCommitOrRollback() {
        Nuts.require();
        NPath location = NPath.of(System.getProperty("java.io.tmpdir") + "/naru-data-store-file-" + System.nanoTime());
        NaruFileDataStoreProvider provider = new NaruFileDataStoreProvider();
        NaruDataStore store = provider.open(new NaruDataStoreConfig("test", location, location,
                location.resolve("store"), Map.of())).get();
        store.transaction(s -> {
            s.put("a", NElement.ofString("one"));
            s.put("b", NElement.ofString("two"));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> store.transaction(s -> {
            s.put("a", NElement.ofString("bad"));
            throw new IllegalStateException("rollback");
        }));
        assertEquals("one", store.get("a").get().asStringValue().get());
        assertEquals(java.util.List.of("a", "b"), store.keys(""));

        NaruDataStore reopened = provider.open(new NaruDataStoreConfig("test", location, location,
                location.resolve("store"), Map.of())).get();
        assertEquals("two", reopened.get("b").get().asStringValue().get());
    }
}
