package net.thevpc.naru.ext.store.jdbc;

import net.thevpc.naru.api.store.NaruDataStore;
import net.thevpc.naru.api.store.NaruDataStoreConfig;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NaruJdbcDataStoreTest {
    @Test
    void jdbcRecordsAreNamespaceScopedDurableAndTransactional() {
        Nuts.require();
        NPath location = NPath.of(System.getProperty("java.io.tmpdir") + "/naru-jdbc-store-" + System.nanoTime());
        NaruJdbcDataStoreProvider provider = new NaruJdbcDataStoreProvider();
        NaruDataStoreConfig config = new NaruDataStoreConfig("one", location, location, location,
                Map.of("naru.jdbc.url", "jdbc:h2:mem:naru_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"));
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
                location, config.properties())).get();
        assertTrue(otherNamespace.keys("").isEmpty());
    }

    @Test
    void jdbcSessionStorePersistsIncrementalHistoryVersionsAndAudit() {
        Nuts.require();
        String url = "jdbc:h2:mem:session_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        NaruStoreConfig config = new NaruStoreConfig(NPath.of("/tmp/naru-jdbc-test"), NPath.of("/tmp/naru-jdbc-test/.naru"),
                NaruSessionScope.PRIVATE, Map.of("naru.jdbc.url", url));
        NaruSessionStoreFactory factory = new NaruJdbcSessionStoreFactory();
        NaruSessionStore store = factory.open(config).get();
        NaruSessionData data = new NaruSessionData().uuid("s-1").name("one")
                .creationInstant(Instant.parse("2024-01-01T00:00:00Z"))
                .modificationInstant(Instant.parse("2024-01-02T00:00:00Z"));
        store.create(data, NaruSessionScope.PRIVATE);
        store.saveTask("s-1", NaruSessionScope.PRIVATE, NaruTaskState.ofInline(7,
                NElement.ofObjectBuilder().set("id", 7).set("name", "task")
                        .set("history", NElement.ofArray(NaruMessage.user("first").toElement())).build()));
        NaruTaskState saved = store.loadTask("s-1", NaruSessionScope.PRIVATE, 7).get();
        String firstId = saved.historyIds().getFirst();
        NaruHistorySave appended = store.saveHistory("s-1", NaruSessionScope.PRIVATE, 7,
                List.of(NaruMessage.user("first"), NaruMessage.user("second")));
        assertEquals(1, appended.created());
        assertEquals(firstId, appended.ids().getFirst());
        assertEquals(List.of("first", "second"), store.loadHistory("s-1", NaruSessionScope.PRIVATE, 7)
                .stream().map(NaruMessage::getContent).toList());

        NaruSessionState snapshot = new NaruSessionState(data);
        snapshot.tasks().put(7L, store.loadTask("s-1", NaruSessionScope.PRIVATE, 7).get());
        NaruVersionRef version = store.versions("s-1", NaruSessionScope.PRIVATE).commit("checkpoint", "start", () -> snapshot);
        assertTrue(store.versions("s-1", NaruSessionScope.PRIVATE).restore("start", restored ->
                assertEquals(2, restored.tasks().get(7L).history().size())));

        NaruAuditRecord audit = NaruAuditRecord.builder().timestamp(Instant.now()).taskId(7)
                .sessionUuid("s-1").provider("test").build();
        store.audit("s-1", NaruSessionScope.PRIVATE).append(audit, "request", "response");
        assertEquals("request", store.audit("s-1", NaruSessionScope.PRIVATE).read(1, null).getFirst().requestBody().asStringValue().get());
        assertEquals("s-1", store.findUuid("one").get());
        assertEquals(version.seq(), store.versions("s-1", NaruSessionScope.PRIVATE).head().get().seq());
    }

    @Test
    void jdbcSessionStoreKeepsPublicPrivateAndProjectStoresSeparate() {
        Nuts.require();
        String url = "jdbc:h2:mem:scope_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        NaruSessionStoreFactory factory = new NaruJdbcSessionStoreFactory();
        NaruStoreConfig firstProject = new NaruStoreConfig(NPath.of("/tmp/naru-project-one"), NPath.of("/tmp/naru-project-one/.naru"),
                NaruSessionScope.PRIVATE, Map.of("naru.jdbc.url", url));
        NaruStoreConfig secondProject = new NaruStoreConfig(NPath.of("/tmp/naru-project-two"), NPath.of("/tmp/naru-project-two/.naru"),
                NaruSessionScope.PRIVATE, Map.of("naru.jdbc.url", url));
        NaruSessionStore one = factory.open(firstProject).get();
        NaruSessionStore two = factory.open(secondProject).get();
        one.create(new NaruSessionData().uuid("public-1").name("public"), NaruSessionScope.PUBLIC);
        one.create(new NaruSessionData().uuid("private-1").name("private"), NaruSessionScope.PRIVATE);

        assertEquals(List.of("public-1"), one.list(NaruSessionScope.PUBLIC).stream().map(NaruSessionRef::uuid).toList());
        assertEquals(List.of("private-1"), one.list(NaruSessionScope.PRIVATE).stream().map(NaruSessionRef::uuid).toList());
        assertEquals(NaruSessionScope.PUBLIC, one.scopeOf("public-1").get());
        assertEquals(NaruSessionScope.PRIVATE, one.scopeOf("private-1").get());
        assertTrue(two.list().isEmpty(), "projects sharing a DataSource must remain isolated");

        one.move("public-1", NaruSessionScope.PUBLIC, NaruSessionScope.PRIVATE);
        assertTrue(one.list(NaruSessionScope.PUBLIC).isEmpty());
        assertEquals(List.of("private-1", "public-1"), one.list(NaruSessionScope.PRIVATE).stream()
                .map(NaruSessionRef::uuid).sorted().toList());
        assertEquals(NaruSessionScope.PRIVATE, one.scopeOf("public-1").get());
        one.close();
        two.close();
    }
}
