package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Providers and protocols compiled against the session/task signatures keep working once the
 * engine calls the {@link NaruModelContext} ones, and the reverse; implementing neither fails
 * with a message rather than the two defaults calling each other forever.
 */
public class NaruModelContextCompatTest {

    @BeforeAll
    static void setUpWorkspace() {
        Nuts.require();
    }

    /** Written before NaruModelContext: only the deprecated signatures. */
    @SuppressWarnings("deprecation")
    static class LegacyProvider extends Base {
        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of("legacy-model");
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.of("legacy-key");
        }

        @Override
        public boolean isAvailable(NaruSession session) {
            return false;
        }
    }

    static class CurrentProvider extends Base {
        @Override
        public List<String> findModelIds(NaruModelContext context) {
            return List.of("current-model");
        }
    }

    static class EmptyProvider extends Base {
    }

    @SuppressWarnings("deprecation")
    static class LegacyProtocol implements NaruModelProtocol {
        @Override
        public NaruResponse chat(NaruModelRequest request, NaruTask task) {
            return new NaruResponse(NaruMessage.assistant("legacy"), true);
        }

        @Override
        public NaruModelCapabilities getCapabilities() {
            return null;
        }
    }

    @Test
    public void aLegacyProviderIsServedThroughTheContextSignatures() {
        LegacyProvider p = new LegacyProvider();
        NaruModelContext context = NaruModelContext.standalone();
        Assertions.assertEquals(List.of("legacy-model"), p.findModelIds(context));
        Assertions.assertEquals("legacy-key", p.apiKey(context).get());
        Assertions.assertFalse(p.isAvailable(context), "an overridden old default must not be bypassed");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void aCurrentProviderIsServedThroughTheDeprecatedSignatures() {
        CurrentProvider p = new CurrentProvider();
        Assertions.assertEquals(List.of("current-model"), p.findModelIds((NaruSession) null));
        Assertions.assertTrue(p.isAvailable((NaruSession) null), "an untouched default keeps its value");
    }

    @Test
    public void implementingNeitherFailsInsteadOfRecursing() {
        UnsupportedOperationException e = Assertions.assertThrows(UnsupportedOperationException.class,
                () -> new EmptyProvider().findModelIds(NaruModelContext.standalone()));
        Assertions.assertTrue(e.getMessage().contains("findModelIds"), e.getMessage());
    }

    @Test
    public void aLegacyProtocolAnswersAContextCall() {
        NaruResponse r = new LegacyProtocol().chat(
                new NaruModelRequest(List.of(NaruMessage.user("hi")), java.util.Map.of()),
                NaruModelContext.standalone());
        Assertions.assertEquals("legacy", r.getMessage().getFilteredContent());
    }

    /** Everything a provider must have besides the methods under test. */
    abstract static class Base implements NaruModelProvider {
        @Override
        public String name() {
            return "compat";
        }

        @Override
        public void setParam(String name, String value) {
        }

        @Override
        public NOptional<String> getParam(String name) {
            return NOptional.ofEmpty();
        }

        @Override
        public Set<String> getParamNames() {
            return Set.of();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }
}
