package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NaruJdbcDataStoreTest {
    @Test
    void jdbcRecordsAreNamespaceScopedDurableAndTransactional() {
        Nuts.require();
        NPath location = NPath.of(System.getProperty("java.io.tmpdir") + "/naru-jdbc-store-" + System.nanoTime());
        NaruJdbcDataStoreProvider provider = new NaruJdbcDataStoreProvider();
        NaruDataStoreConfig config = new NaruDataStoreConfig("one", location, location, location,
                null, Map.of("naru.jdbc.url", "jdbc:h2:mem:naru_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
        NaruDataStore store = provider.open(config).get();
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

        NaruDataStore otherNamespace = provider.open(new NaruDataStoreConfig("two", location, location,
                location, null, config.properties())).get();
        assertTrue(otherNamespace.keys("").isEmpty());
    }
}
