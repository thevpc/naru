package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * The value type behind /model add|registered|update: provider required, wire
 * ids written as provider rejected, protocol normalized, $NAME resolved on read,
 * literal secrets masked.
 */
public class NaruModelRegistrationTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();    }

    @Test
    public void identityIsProviderOrProtocol() {
        // a string provider must name a type
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("x", (String) null));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("x", "  "));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of((String) null, "gemini"));

        // a non-object element is a broken hand-edit, not a registration
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruModelRegistration.of("x", NElement.ofString("gemini")));
    }

    @Test
    public void genericEndpointNamesNoProviderAndIsAddressedByTheCustomType() {
        // --protocol alone: a generic endpoint with no stored provider
        NaruModelRegistration r = NaruModelRegistration.builder("ep")
                .put("protocol", NElement.ofString("openai"))
                .put("url", NElement.ofString("https://x"))
                .put("models", NElement.ofString("a,b"))
.build();
        Assertions.assertTrue(r.isGeneric(), "no provider param means no provider type");
        Assertions.assertEquals("custom", r.endpointType(), "... but a provider class still serves its wire");
        Assertions.assertEquals("openai", r.protocol().get());
        Assertions.assertFalse(r.provider().isPresent(), "nothing persists provider=custom");
        Assertions.assertEquals(r, NaruModelRegistration.of(r.id(), r.toElement()), "generic endpoint round-trips");
    }

    @Test
    public void nonProviderIdsAreRejected() {
        // openai is a provider type now: accepted, stored as-is
        NaruModelRegistration o = NaruModelRegistration.builder("ep").put("provider", NElement.ofString("openai")).build();
        Assertions.assertEquals("openai", o.provider().get());

        // wire protocol ids without a provider type are rejected, never normalized (clean cut)
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.builder("ep").put("provider", NElement.ofString("anthropic")).build());
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.builder("ep").put("provider", NElement.ofString("custom")).build());
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("ep", "anthropic"));

        // the openapi rejection carries the rename hint (openai is now a type too)
        NIllegalArgumentException ex = Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruModelRegistration.builder("ep").put("provider", NElement.ofString("openapi")).build());
        Assertions.assertTrue(ex.getMessage().contains("openai"));

        // 'wire' is the pre-rename spelling of the internal custom type: rejected with a hint
        NIllegalArgumentException wireEx = Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruModelRegistration.builder("ep").put("provider", NElement.ofString("wire")).build());
        Assertions.assertTrue(wireEx.getMessage().contains("custom"));

        // gemini as a provider is the built-in provider, never a shorthand
        NaruModelRegistration r = NaruModelRegistration.of("personal", "gemini");
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertFalse(r.protocol().isPresent());

        // ... its native wire shape is an explicit protocol on top of the type
        r = NaruModelRegistration.builder("native").put("provider", NElement.ofString("gemini")).put("protocol", NElement.ofString("gemini")).build();
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertEquals("gemini", r.protocol().get());
    }

    @Test
    public void typedAccessors() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("provider", "gemini");
        b.set("model", "gemini-2.5-pro");
        b.set("temperature", 0.2f);
        b.set("contextLength", 163840L);
        b.set("maxTokens", 8192);
        b.set("stream", true);
        b.set("stop", NElement.ofArray(NElement.ofString("<eos>"), NElement.ofString("<bos>")));
        b.set("thinkingTags", "begin,end");
        NaruModelRegistration r = NaruModelRegistration.of("personal", b.build());

        Assertions.assertEquals("personal", r.id());
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertEquals("gemini-2.5-pro", r.model().get());
        Assertions.assertEquals(0.2f, r.temperature().get());
        Assertions.assertEquals(163840, r.contextLength().get());
        Assertions.assertEquals(8192, r.maxTokens().get());
        // an undeclared knob stays in extras: reading it is a different call from reading a field
        Assertions.assertEquals(Boolean.TRUE, r.extra("stream").get().asBooleanValue().get());
        Assertions.assertEquals(List.of("<eos>", "<bos>"), r.stop());
        Assertions.assertEquals(List.of("begin", "end"),
                NaruRegistrationFields.listValueOf(r.thinkingTags().get()));
        Assertions.assertFalse(r.extra("absent").isPresent());
    }

    @Test
    public void withFieldDoesNotMutate() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("provider", "gemini");
        b.set("temperature", 0.2f);
        NaruModelRegistration r = NaruModelRegistration.of("personal", b.build());

        NaruModelRegistration colder = r.with(NaruRegistrationFields.TEMPERATURE, 0.1f);
        Assertions.assertNotEquals(r, colder);
        Assertions.assertEquals(0.2f, r.temperature().get());
        Assertions.assertEquals(0.1f, colder.temperature().get());

        NaruModelRegistration noTemp = r.with(NaruRegistrationFields.TEMPERATURE, null);
        Assertions.assertFalse(noTemp.temperature().isPresent());
        Assertions.assertTrue(r.temperature().isPresent());
        // provider can never be dropped
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> r.with(NaruRegistrationFields.PROVIDER, null));
    }

    @Test
    public void elementRoundTrip() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("protocol", "openai");
        b.set("url", "https://api.example.com/v1");
        b.set("apiKey", "$EXAMPLE_API_KEY");
        b.set("temperature", 0.7f);
        NaruModelRegistration r = NaruModelRegistration.of("example", b.build());

        Assertions.assertTrue(r.isGeneric());
        Assertions.assertEquals("openai", r.protocol().get());
        Assertions.assertEquals(r, NaruModelRegistration.of(r.id(), r.toElement()));
    }

    @Test
    public void interpolationResolvesOnRead() {
        Assertions.assertFalse(NaruModelRegistration.isInterpolated("plain"));
        Assertions.assertFalse(NaruModelRegistration.isInterpolated("100$"));
        Assertions.assertFalse(NaruModelRegistration.isInterpolated(null));
        Assertions.assertTrue(NaruModelRegistration.isInterpolated("$AAA"));
        Assertions.assertTrue(NaruModelRegistration.isInterpolated("${AAA}"));

        Map<String, String> env = Map.of("AAA", "v1", "BBB", "v2");
        Assertions.assertEquals("v1", NaruModelRegistration.interpolate("$AAA", env::get).get());
        Assertions.assertEquals("v1 value", NaruModelRegistration.interpolate("$AAA value", env::get).get());
        Assertions.assertEquals("pre v2", NaruModelRegistration.interpolate("pre ${BBB}", env::get).get());
        // identity without any reference, even with a resolver that finds nothing
        Assertions.assertEquals("abc", NaruModelRegistration.interpolate("abc", x -> null).get());
        Assertions.assertEquals("100$", NaruModelRegistration.interpolate("100$", env::get).get());
        // an unset reference empties the whole value: fall through, never send "$CC"
        Assertions.assertFalse(NaruModelRegistration.interpolate("$CC", env::get).isPresent());
        Assertions.assertFalse(NaruModelRegistration.interpolate("pre $CC", env::get).isPresent());

        // resolve() goes through the real environment: an unset variable is not a value
        NaruModelRegistration r = NaruModelRegistration.builder("personal").put("provider", NElement.ofString("gemini")).put("apiKey", NElement.ofString("$NARU_TEST_UNSET_VAR_42")).build();
        // an unset reference is not a value, and neither is an unset endpoint
        Assertions.assertFalse(r.apiKeyResolved(null).isPresent());
        Assertions.assertFalse(r.url().isPresent());
    }

    @Test
    public void literalSecretsAreMasked() {
        Assertions.assertTrue(NaruModelRegistration.isSecretParam("apiKey"));
        Assertions.assertTrue(NaruModelRegistration.isSecretParam("API_KEY"));
        Assertions.assertTrue(NaruModelRegistration.isSecretParam("api_key"));
        Assertions.assertFalse(NaruModelRegistration.isSecretParam("url"));

        Assertions.assertTrue(NaruModelRegistration.isSecretLiteral("apiKey", NElement.ofString("sk-abcdef1234")));
        Assertions.assertFalse(NaruModelRegistration.isSecretLiteral("apiKey", NElement.ofString("$GEMINI_KEY_A")));
        Assertions.assertFalse(NaruModelRegistration.isSecretLiteral("url", NElement.ofString("sk-abcdef1234")));

        NaruModelRegistration r = NaruModelRegistration.builder("personal").put("provider", NElement.ofString("gemini")).put("apiKey", NElement.ofString("sk-abcdef1234")).build();
        // toString never leaks, even on the unmasked value
        Assertions.assertFalse(r.toString().contains("sk-abcdef1234"));
        Assertions.assertTrue(r.toString().contains("sk-***1234"));
        // masked() is the display copy; the stored value stays intact
        Assertions.assertEquals("sk-***1234", r.masked().apiKey().get());
        Assertions.assertEquals("sk-abcdef1234", r.apiKey().get());

        NaruModelRegistration ref = NaruModelRegistration.builder("work").put("provider", NElement.ofString("gemini")).put("apiKey", NElement.ofString("$GEMINI_KEY_A")).build();
        // a reference names a variable, not a value: shown as-is
        Assertions.assertTrue(ref.toString().contains("$GEMINI_KEY_A"));
        Assertions.assertEquals("$GEMINI_KEY_A", ref.masked().apiKey().get());
    }

    @Test
    public void envValueLayersSessionEnvThenAgentEnvThenSystemEnv() {
        NaruSession session = layeredSession(
                Map.of("SHARED", "session-wins", "ONLY_SESSION", "s-value",
                        "BLANKED", "", "BLANK_TO_AGENT", ""),
                Map.of("SHARED", "agent-value", "ONLY_AGENT", "a-value",
                        "BLANKED", "", "BLANK_TO_AGENT", "fallback"));

        // the most specific scope wins ...
        Assertions.assertEquals("session-wins", NaruModelRegistration.envValue(session, "SHARED"));
        Assertions.assertEquals("s-value", NaruModelRegistration.envValue(session, "ONLY_SESSION"));
        Assertions.assertEquals("a-value", NaruModelRegistration.envValue(session, "ONLY_AGENT"));
        // ... a blank value counts as unset: the lookup falls through to the next
        // layer instead of locking the name out ...
        Assertions.assertEquals("fallback", NaruModelRegistration.envValue(session, "BLANK_TO_AGENT"));
        // ... and when no layer defines the name, there is no value at all
        Assertions.assertNull(NaruModelRegistration.envValue(session, "BLANKED"));
        Assertions.assertNull(NaruModelRegistration.envValue(session, "NARU_TEST_NO_SUCH_VAR_42"));
        // the system environment is the last layer (and the only one without a session)
        Assertions.assertEquals(System.getenv("PATH"), NaruModelRegistration.envValue(session, "PATH"));
        Assertions.assertEquals(System.getenv("PATH"), NaruModelRegistration.envValue(null, "PATH"));
        Assertions.assertNull(NaruModelRegistration.envValue(null, "NARU_TEST_NO_SUCH_VAR_42"));

        // $NAME references resolve through the same layers
        Assertions.assertEquals("s-value",
                NaruModelRegistration.interpolate("$ONLY_SESSION", NaruModelRegistration.envResolver(session)).get());
        Assertions.assertEquals("a-value",
                NaruModelRegistration.interpolate("${ONLY_AGENT}", NaruModelRegistration.envResolver(session)).get());
        Assertions.assertFalse(NaruModelRegistration.interpolate("$NARU_TEST_NO_SUCH_VAR_42",
                NaruModelRegistration.envResolver(session)).isPresent());
    }

    /**
     * A session whose env layers are plain maps: enough to pin the precedence
     * rules of {@code envValue} without any file or workspace.
     */
    private static NaruSession layeredSession(Map<String, String> sessionEnv, Map<String, String> agentEnv) {
        NaruEnv env = (NaruEnv) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(), new Class<?>[]{NaruEnv.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    if (method.getName().equals("get")) {
                        String k = (String) args[0];
                        return agentEnv.containsKey(k)
                                ? NOptional.of(NElement.ofString(agentEnv.get(k)))
                                : NOptional.ofEmpty();
                    }
                    return defaultValue(method.getReturnType());
                });
        NaruWorkspace agent = (NaruWorkspace) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(), new Class<?>[]{NaruWorkspace.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    if (method.getName().equals("env")) {
                        return env;
                    }
                    return defaultValue(method.getReturnType());
                });
        return (NaruSession) Proxy.newProxyInstance(
                NaruSession.class.getClassLoader(), new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, args);
                    }
                    if (method.getName().equals("getSessionEnv")) {
                        String k = (String) args[0];
                        return sessionEnv.containsKey(k)
                                ? NOptional.of(sessionEnv.get(k))
                                : NOptional.ofEmpty();
                    }
                    if (method.getName().equals("agent")) {
                        return agent;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object objectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "layeredSession";
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return proxy == args[0];
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
