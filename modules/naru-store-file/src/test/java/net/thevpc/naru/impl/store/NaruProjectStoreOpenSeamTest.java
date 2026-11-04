package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectDraft;
import net.thevpc.naru.api.project.NaruProjectQuery;
import net.thevpc.naru.api.project.NaruProjectStore;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.project.NaruProjectStoreConfig;
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.artifact.NDefinition;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.reflect.NClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code NaruProjectStore.open} is the seam a host uses to read a folder that is not its own
 * workspace — the desktop's group form inspects the folder the user just picked. It must find
 * the file store through the SPI, honour a requested backend name, and refuse loudly when a
 * named backend is missing rather than quietly writing somewhere else.
 */
@Timeout(60)
public class NaruProjectStoreOpenSeamTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NClassLoader classLoader() {
        return NClassLoader.of("naru-project-store-seam-test",
                NaruProjectStoreOpenSeamTest.class.getClassLoader(), new NDefinition[0]);
    }

    @Test
    public void opensAForeignFolderByDefault() {
        NPath home = NPath.ofTempFolder("naru-store-seam-" + System.nanoTime());
        NaruProjectStore store = NaruProjectStore.open(new NaruProjectStoreConfig(home, null),
                null, classLoader());
        Assertions.assertTrue(store instanceof NaruFileProjectStore, store.getClass().getName());
        NaruProject created = store.create(NaruProjectDraft.of("Backend", home, null)
                .icon("spark").color("#F97316").description("long load"));
        // a second open reads the same folder the way a fresh host would: from disk, not memory
        NaruProjectStore reopened = NaruProjectStore.open(new NaruProjectStoreConfig(home, null),
                null, classLoader());
        NaruPage<NaruProject> page = reopened.findByQuery(
                new NaruProjectQuery(null, null, NaruUserFilter.any(), null, 10));
        Assertions.assertEquals(1, page.items().size());
        NaruProject row = page.items().get(0);
        Assertions.assertEquals("Backend", row.name());
        Assertions.assertEquals(created.id(), row.id());
        Assertions.assertEquals("spark", row.icon());
        Assertions.assertEquals("#F97316", row.color());
        Assertions.assertEquals("long load", row.description());
    }

    @Test
    public void honoursARequestedStoreName() {
        NPath home = NPath.ofTempFolder("naru-store-seam-" + System.nanoTime());
        NaruProjectStore store = NaruProjectStore.open(new NaruProjectStoreConfig(home, null),
                NaruFileProjectStoreFactory.NAME, classLoader());
        Assertions.assertNotNull(store.create(NaruProjectDraft.of("Named", home, null)));
    }

    @Test
    public void refusesAnUnknownStoreNameLoudly() {
        NPath home = NPath.ofTempFolder("naru-store-seam-" + System.nanoTime());
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () ->
                NaruProjectStore.open(new NaruProjectStoreConfig(home, null),
                        "postgres", classLoader()));
        Assertions.assertTrue(e.getMessage().contains("no project store named 'postgres'"),
                e.getMessage());
    }
}
