package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value type behind /model add|registered|update: provider required, wire-id
 * shorthands normalized, $NAME resolved on read, literal secrets masked.
 */
public class NaruModelRegistrationTest {

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void providerIsRequired() {
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("x", (String) null));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("x", "  "));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of((String) null, "gemini"));

        Map<String, NElement> noProvider = new LinkedHashMap<>();
        noProvider.put("url", NElement.ofString("https://x"));
        Assertions.assertThrows(NIllegalArgumentException.class, () -> NaruModelRegistration.of("x", noProvider));
        // a non-object element is a broken hand-edit, not a registration
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruModelRegistration.of("x", NElement.ofString("gemini")));
    }

    @Test
    public void wireShorthandsNormalize() {
        NaruModelRegistration r = NaruModelRegistration.of("ep",
                Map.of("provider", NElement.ofString("openapi")));
        Assertions.assertEquals("wire", r.provider());
        Assertions.assertEquals("openapi", r.protocol().get());

        r = NaruModelRegistration.of("ep",
                Map.of("provider", NElement.ofString("anthropic")));
        Assertions.assertEquals("wire", r.provider());
        Assertions.assertEquals("anthropic", r.protocol().get());

        // an explicit protocol survives the shorthand
        r = NaruModelRegistration.of("ep", Map.of(
                "provider", NElement.ofString("openapi"),
                "protocol", NElement.ofString("gemini")));
        Assertions.assertEquals("wire", r.provider());
        Assertions.assertEquals("gemini", r.protocol().get());

        // gemini as a provider is the built-in provider, never a shorthand
        r = NaruModelRegistration.of("personal", "gemini");
        Assertions.assertEquals("gemini", r.provider());
        Assertions.assertFalse(r.protocol().isPresent());

        // ... its native wire shape is an explicit protocol on top of the type
        r = NaruModelRegistration.of("native", Map.of(
                "provider", NElement.ofString("gemini"),
                "protocol", NElement.ofString("gemini")));
        Assertions.assertEquals("gemini", r.provider());
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
        Assertions.assertEquals("gemini", r.provider());
        Assertions.assertEquals("gemini-2.5-pro", r.stringValue("model").get());
        Assertions.assertEquals(0.2f, r.floatValue("temperature").get());
        Assertions.assertEquals(163840L, r.longValue("contextLength").get());
        Assertions.assertEquals(8192, r.intValue("maxTokens").get());
        Assertions.assertTrue(r.booleanValue("stream").get());
        Assertions.assertEquals(List.of("<eos>", "<bos>"), r.stringList("stop"));
        Assertions.assertEquals(List.of("begin", "end"), r.stringList("thinkingTags"));
        Assertions.assertFalse(r.param("absent").isPresent());
        Assertions.assertTrue(r.stringValue("absent").isEmpty());
    }

    @Test
    public void withParamDoesNotMutate() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("provider", "gemini");
        b.set("temperature", 0.2f);
        NaruModelRegistration r = NaruModelRegistration.of("personal", b.build());

        NaruModelRegistration colder = r.withParam("temperature", NElement.of(0.1f));
        Assertions.assertNotEquals(r, colder);
        Assertions.assertEquals(0.2f, r.floatValue("temperature").get());
        Assertions.assertEquals(0.1f, colder.floatValue("temperature").get());

        NaruModelRegistration noTemp = r.withoutParam("temperature");
        Assertions.assertFalse(noTemp.param("temperature").isPresent());
        Assertions.assertTrue(r.param("temperature").isPresent());
        // provider can never be dropped
        Assertions.assertThrows(NIllegalArgumentException.class, () -> r.withoutParam("provider"));
    }

    @Test
    public void elementRoundTrip() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        b.set("provider", "wire");
        b.set("protocol", "openapi");
        b.set("url", "https://api.example.com/v1");
        b.set("apiKey", "$EXAMPLE_API_KEY");
        b.set("temperature", 0.7f);
        NaruModelRegistration r = NaruModelRegistration.of("example", b.build());

        Assertions.assertEquals(r, NaruModelRegistration.of(r.id(), r.toElement()));
        Assertions.assertEquals(r, NaruModelRegistration.of(r.id(), r.params()));
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
        NaruModelRegistration r = NaruModelRegistration.of("personal", Map.of(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("$NARU_TEST_UNSET_VAR_42")));
        Assertions.assertFalse(r.resolve("apiKey").isPresent());
        Assertions.assertFalse(r.resolve("url").isPresent());
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

        NaruModelRegistration r = NaruModelRegistration.of("personal", Map.of(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("sk-abcdef1234")));
        // toString never leaks, even on the unmasked value
        Assertions.assertFalse(r.toString().contains("sk-abcdef1234"));
        Assertions.assertTrue(r.toString().contains("sk-***1234"));
        // masked() is the display copy; the stored value stays intact
        Assertions.assertEquals("sk-***1234", r.masked().stringValue("apiKey").get());
        Assertions.assertEquals("sk-abcdef1234", r.stringValue("apiKey").get());

        NaruModelRegistration ref = NaruModelRegistration.of("work", Map.of(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("$GEMINI_KEY_A")));
        // a reference names a variable, not a value: shown as-is
        Assertions.assertTrue(ref.toString().contains("$GEMINI_KEY_A"));
        Assertions.assertEquals("$GEMINI_KEY_A", ref.masked().stringValue("apiKey").get());
    }
}
