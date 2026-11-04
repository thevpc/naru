package net.thevpc.naru.api.model;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 of the typed-registration plan
 * ({@code documentation/plans/model-registration-typed-fields.md}): the parameters a registration is
 * made of became fields, and the map became a derived view.
 *
 * <p>What this pins is the part that must stay <b>identical</b> — the stored form round-trips, an
 * undeclared parameter survives, interpolation and masking did not move — and the one place where
 * behaviour deliberately changed: an alias-spelled parameter now <em>is</em> the canonical field, so
 * a key written {@code api_key} or an endpoint written {@code host} resolves the way its author meant
 * instead of being filed away and then never read back.
 */
public class NaruModelRegistrationTypedFieldsTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NElement obj(Object... kv) {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            b.set((String) kv[i], (NElement) kv[i + 1]);
        }
        return b.build();
    }

    private static List<String> keysOf(NElement e) {
        List<String> out = new ArrayList<>();
        for (NPairElement p : e.asListContainer().get().namedPairs()) {
            out.add(p.key().asStringValue().get());
        }
        return out;
    }

    // ── the storage boundary round-trips ──────────────────────────────────────────

    @Test
    void aProviderInstanceRoundTripsThroughItsStoredForm() {
        NElement stored = obj(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("$GEMINI_KEY_A"),
                "model", NElement.ofString("gemini-3.8-flash"),
                "temperature", NElement.of(0.7f));
        NaruModelRegistration r = NaruModelRegistration.of("personal", stored);
        Assertions.assertEquals(r, NaruModelRegistration.of(r.id(), r.toElement()), "round-trips");
        Assertions.assertEquals("gemini", r.provider().get());
        Assertions.assertEquals("$GEMINI_KEY_A", r.apiKey().get());
        Assertions.assertEquals("gemini-3.8-flash", r.model().get());
        Assertions.assertEquals(0.7f, r.temperature().get());
    }

    @Test
    void aSecondWritePassProducesTheSameElementAsTheFirst() {
        // a store merges by id, but a file that changed shape on every save would look like a bug
        // in the editor and defeat any byte-level comparison of two saves of the same state
        NElement stored = obj(
                "protocol", NElement.ofString("OpenAI"),
                "url", NElement.ofString("http://localhost:4000/v1"),
                "models", NElement.ofString("qwen3-32b,llama3.1:8b"),
                "someFutureKnob", NElement.ofString("keep me"));
        NaruModelRegistration r = NaruModelRegistration.of("litellm", stored);
        NElement first = r.toElement();
        NElement second = NaruModelRegistration.of("litellm", first).toElement();
        Assertions.assertEquals(first, second, "the written form is stable across a second pass");
    }

    @Test
    void theWrittenFormPutsIdentityFirstThenEndpointPinsSamplingFlagsTimeouts() {
        NaruModelRegistration r = NaruModelRegistration.builder("full")
                .provider("gemini")
                .maxRetries(3)
                .apiKey("sk-abcdef1234")
                .temperature(0.2f)
                .model("gemini-3.8-flash")
                .protocol("gemini")
                .enabled(Boolean.FALSE)
                .build();
        Assertions.assertEquals(
                List.of("provider", "protocol", "apiKey", "model", "temperature", "enabled", "maxRetries"),
                keysOf(r.toElement()),
                "vocabulary order is file order: a hand-edited registration reads top-down");
    }

    // ── what core does not declare survives ───────────────────────────────────────

    @Test
    void anUndeclaredParameterIsKeptVerbatimInExtrasAndWrittenBack() {
        NElement knob = NElement.ofObjectBuilder().set("bin", NElement.ofString("/opt/llama")).build();
        NaruModelRegistration r = NaruModelRegistration.of("gpu", obj(
                "provider", NElement.ofString("llama.cpp"),
                "modelFile", knob));
        Assertions.assertEquals(List.of("modelFile"), List.copyOf(r.extras().keySet()),
                "extras hold exactly the undeclared keys, nothing else");
        Assertions.assertEquals(knob, r.extra("modelFile").get(), "as stored, not re-rendered");
        Assertions.assertEquals(r, NaruModelRegistration.of("gpu", r.toElement()),
                "an unknown knob is not lost by a save");
    }

    @Test
    void theDirectiveCompletionNamesAreAllDeclaredFields() {
        // NaruModelDirective keeps its own list for Tab completion today; phase 4 derives it from the
        // table, and this is what makes that safe: every name it offers really is a field.
        String[] offered = {
                "provider", "protocol", "model", "models", "apiKey", "url",
                "temperature", "contextLength", "nucleusThreshold", "candidateCount",
                "maxTokens", "maxRetries", "stop", "thinkingTags", "chatPath",
                "tools", "probe", "enabled"};
        for (String name : offered) {
            Assertions.assertNotNull(NaruModelRegistration.field(name), name + " must be declared");
        }
    }

    // ── values are read leniently, because these files are hand-typed ─────────────

    @Test
    void aNumberOrFlagWrittenAsAWordIsStillTheDeclaredKind() {
        NaruModelRegistration r = NaruModelRegistration.of("ep", obj(
                "protocol", NElement.ofString("openai"),
                "temperature", NElement.ofString("0.7"),
                "contextLength", NElement.of(4096L),
                "enabled", NElement.ofString("false"),
                "models", NElement.ofString("a, b ,,c")));
        Assertions.assertEquals(0.7f, r.temperature().get(), "\"0.7\" is a float");
        Assertions.assertEquals(4096, r.contextLength().get(), "a long element is an int parameter");
        Assertions.assertEquals(Boolean.FALSE, r.enabled().get(), "\"false\" is the flag off");
        Assertions.assertEquals(List.of("a", "b", "c"), r.models(), "a comma word is a list");
    }

    @Test
    void anUnsetParameterIsNotFalseOrZero() {
        NaruModelRegistration unset = NaruModelRegistration.of("a", "gemini");
        Assertions.assertFalse(unset.enabled().isPresent(), "unset means the provider's own default");
        Assertions.assertFalse(unset.temperature().isPresent());
        Assertions.assertFalse(unset.url().isPresent());
        Assertions.assertTrue(unset.models().isEmpty());
        NaruModelRegistration off = NaruModelRegistration.builder("b").put("provider", NElement.ofString("gemini")).put("enabled", NElement.of(false)).build();
        Assertions.assertEquals(Boolean.FALSE, off.enabled().get());
        Assertions.assertNotEquals(unset, off, "off and unset are two different registrations");
    }

    // ── alias folding: the deliberate behaviour change ────────────────────────────

    @Test
    void anAliasSpelledKeyIsTheCanonicalFieldAndResolves() {
        // Before: SECRET_PARAMS contained api_key, so the store filed it in the private file, and
        // AbstractNaruModelProvider.apiKey() probed {apiKey, apikey, key} — so it was stored as a
        // secret and then never read. A hand-edited api_key silently did nothing.
        for (String spelling : List.of("apiKey", "apikey", "api_key", "key")) {
            NaruModelRegistration r = NaruModelRegistration.of("p", obj(
                    "provider", NElement.ofString("gemini"),
                    spelling, NElement.ofString("sk-secretvalue")));
            Assertions.assertEquals("sk-secretvalue", r.apiKey().get(), spelling + " names the key");
            List<String> written = keysOf(r.toElement());
            Assertions.assertTrue(written.contains("apiKey"), spelling + " writes the canonical name");
            if (!"apiKey".equals(spelling)) {
                Assertions.assertFalse(written.contains(spelling),
                        "the alias '" + spelling + "' does not survive a save, got " + written);
            }
            Assertions.assertTrue(r.hasLiteralApiKey(), spelling + " reads as a credential");
        }
    }

    @Test
    void oneFieldNamedTwiceIsWonByTheCanonicalSpellingNotByTheOrderFilesWereRead() {
        // A scope is two files, and one field can be named in both: the public half holds an
        // apiKey: $VAR reference, the private half an api_key literal. Last-write-wins would decide
        // the credential from which file the store happened to read last.
        List<NElement> bothOrders = List.of(
                obj("provider", NElement.ofString("gemini"),
                        "apiKey", NElement.ofString("$GEMINI_KEY_A"),
                        "api_key", NElement.ofString("sk-literal")),
                obj("provider", NElement.ofString("gemini"),
                        "api_key", NElement.ofString("sk-literal"),
                        "apiKey", NElement.ofString("$GEMINI_KEY_A")));
        for (NElement stored : bothOrders) {
            NaruModelRegistration r = NaruModelRegistration.of("personal", stored);
            Assertions.assertEquals("$GEMINI_KEY_A", r.apiKey().get());
            Assertions.assertEquals("$GEMINI_KEY_A",
                    r.toBuilder().put("api_key", NElement.ofString("sk-literal")).build().apiKey().get(),
                    "rebuilding keeps the canonical value");
        }
        // two aliases still take the later write: the rule is about the spelling, not about order
        NaruModelRegistration aliases = NaruModelRegistration.of("p", obj(
                "provider", NElement.ofString("gemini"),
                "key", NElement.ofString("first"),
                "api_key", NElement.ofString("second")));
        Assertions.assertEquals("second", aliases.apiKey().get());
    }

    @Test
    void anAliasSpelledEndpointIsTheUrlFieldAndCountsAsLocal() {
        // Before: isLocal() probed url/baseUrl/endpoint/host/baseURL on every read while the wire
        // layer read only url — so host: localhost named an endpoint nothing could reach.
        for (String spelling : List.of("url", "baseUrl", "baseURL", "endpoint", "host")) {
            NaruModelRegistration r = NaruModelRegistration.of("e", obj(
                    "protocol", NElement.ofString("openai"),
                    spelling, NElement.ofString("http://127.0.0.1:11434")));
            Assertions.assertEquals("http://127.0.0.1:11434", r.url().get(), spelling + " names the url");
            Assertions.assertTrue(r.isLocal(), spelling + " is a local endpoint");
            List<String> written = keysOf(r.toElement());
            Assertions.assertTrue(written.contains("url"), spelling + " writes the canonical name");
            if (!"url".equals(spelling)) {
                Assertions.assertFalse(written.contains(spelling),
                        "the alias '" + spelling + "' does not survive a save, got " + written);
            }
        }
    }

    // ── masking and interpolation did not move ────────────────────────────────────

    @Test
    void aLiteralKeyMasksAndAVariableReferenceDoesNot() {
        NaruModelRegistration literal = NaruModelRegistration.of("work", obj(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("sk-abcdef1234")));
        Assertions.assertEquals("sk-***1234", literal.masked().apiKey().get());
        Assertions.assertEquals("sk-abcdef1234", literal.apiKey().get(), "the stored value stays whole");
        Assertions.assertFalse(literal.toString().contains("sk-abcdef1234"), "toString never leaks");

        NaruModelRegistration ref = NaruModelRegistration.of("work", obj(
                "provider", NElement.ofString("gemini"),
                "apiKey", NElement.ofString("$GEMINI_KEY_A")));
        Assertions.assertEquals("$GEMINI_KEY_A", ref.masked().apiKey().get(),
                "a reference names a variable, not a value");
        Assertions.assertFalse(ref.hasLiteralApiKey());
        Assertions.assertFalse(ref.apiKeyResolved(null).isPresent(),
                "an unset reference is not a value, so the caller falls through");
    }

    @Test
    void everyKeySpellingIsTheDeclaredSecretFieldAndAnUndeclaredNameIsPublic() {
        // The four key spellings fold onto apiKey, which the vocabulary declares secret, so the store
        // files it privately and every print masks it — one rule instead of the three that used to
        // disagree about which spellings counted.
        for (String spelling : List.of("apiKey", "apikey", "api_key", "key", "API_KEY")) {
            Assertions.assertTrue(NaruModelRegistration.isSecretParam(spelling), spelling);
        }
        Assertions.assertFalse(NaruModelRegistration.isSecretParam("url"));
        Assertions.assertFalse(NaruModelRegistration.isSecretParam("temperature"));
        // An undeclared name answers false — unchanged from the old SECRET_PARAMS, which only ever
        // held the four spellings above. This is the gap plan phase 3 closes by letting a provider
        // declare its own parameters rather than core guessing by name.
        Assertions.assertFalse(NaruModelRegistration.isSecretParam("someProviderApiKey"));
        Assertions.assertFalse(NaruModelRegistration.isSecretLiteral("someProviderApiKey",
                NElement.ofString("sk-abcdef9999")));
        Assertions.assertTrue(NaruModelRegistration.isSecretLiteral("apiKey",
                NElement.ofString("sk-abcdef9999")), "a literal is a credential");
        Assertions.assertFalse(NaruModelRegistration.isSecretLiteral("apiKey",
                NElement.ofString("$GEMINI_KEY_A")), "a reference names a variable, not a value");
    }

    // ── thinkingTags keeps the shape it was written in ────────────────────────────

    @Test
    void thinkingTagsKeepsBothTheObjectAndThePairSpelling() {
        NaruModelRegistration pairedByHand = NaruModelRegistration.of("t", obj(
                "provider", NElement.ofString("custom-ish"),
                "thinkingTags", obj("openTag", NElement.ofString("<th>"),
                        "closeTag", NElement.ofString("</th>"))));
        Assertions.assertEquals(NaruThinkingTags.of("<th>", "</th>"),
                NaruThinkingTags.of(pairedByHand.thinkingTags().get()),
                "a hand-edited tag object still means its tag pair");

        NaruModelRegistration pairedByFlag = NaruModelRegistration.of("t", obj(
                "provider", NElement.ofString("custom-ish"),
                "thinkingTags", NElement.ofString("<th>,</th>")));
        Assertions.assertEquals(List.of("<th>", "</th>"), NaruRegistrationFields.listValueOf(pairedByFlag.thinkingTags().get()),
                "and so does the comma spelling the directive writes");

        NaruModelRegistration half = NaruModelRegistration.of("t", obj(
                "provider", NElement.ofString("custom-ish"),
                "thinkingTags", NElement.ofArray(NElement.ofString("<th>"))));
        Assertions.assertThrows(RuntimeException.class,
                () -> NaruThinkingTags.of(NaruRegistrationFields.listValueOf(half.thinkingTags().get()).get(0), null),
                "half a pair stays a loud configuration mistake");
    }

    // ── identity, and the shape the map used to fake ──────────────────────────────

    @Test
    void theGenericEndpointIsADistinctStateNotAnAbsentProvider() {
        NaruModelRegistration generic = NaruModelRegistration.of("ep", obj(
                "protocol", NElement.ofString("OpenAI"),
                "url", NElement.ofString("http://host/v1"),
                "models", NElement.ofString("a,b")));
        Assertions.assertTrue(generic.isGeneric());
        Assertions.assertFalse(generic.provider().isPresent(), "nothing declares a provider");
        Assertions.assertEquals("custom", generic.endpointType(), "the internal type word for lookups");
        Assertions.assertEquals("openai", generic.protocol().get(), "the wire id is canonicalized");
        Assertions.assertEquals(List.of("a", "b"), generic.allPins());

        NaruModelRegistration instance = NaruModelRegistration.of("p", "gemini");
        Assertions.assertFalse(instance.isGeneric());
        Assertions.assertEquals("gemini", instance.provider().get());
    }

    @Test
    void anAliasSpelledPinIsSeenByEveryPinReader() {
        NaruModelRegistration r = NaruModelRegistration.of("ep", obj(
                "protocol", NElement.ofString("openai"),
                "url", NElement.ofString("http://host/v1"),
                "model", NElement.ofString("solo")));
        Assertions.assertEquals(List.of("solo"), r.allPins(), "/model use counts it as exactly one");
        Assertions.assertTrue(r.models().isEmpty(), "absent is absent, not empty");
        Assertions.assertFalse(r.storedPairs().stream()
                        .anyMatch(p -> "models".equals(p.key().asStringValue().orNull())),
                "and the store never writes the absent spelling");
    }

    // ── mutation: typed when the name is declared, extras when it is not ───────────

    @Test
    void settingAndClearingGoesThroughTheVocabulary() {
        NaruModelRegistration base = NaruModelRegistration.of("p", "gemini");
        NaruModelRegistration warmed = base.with(NaruRegistrationFields.TEMPERATURE, 0.1f);
        Assertions.assertEquals(0.1f, warmed.temperature().get());
        Assertions.assertEquals(base, warmed.with(NaruRegistrationFields.TEMPERATURE, null),
                "null clears the field");

        NaruModelRegistration extra = base.withExtra("someFutureKnob", NElement.ofString("v"));
        Assertions.assertEquals("v", extra.extra("someFutureKnob").get().asStringValue().get());
        Assertions.assertTrue(extra.withExtra("someFutureKnob", null).extras().isEmpty());

        // authoring routes a stored pair by name: the alias folds, the kind is parsed
        Assertions.assertEquals(0.1f, base.toBuilder().put("temperature", NElement.ofString("0.1")).build().temperature().get(),
                "a word given to a float parameter is parsed, not kept as a word");
        Assertions.assertEquals(warmed, base.toBuilder().put("temperature", NElement.of(0.1f)).build());
    }

    @Test
    void droppingTheProviderIsRefusedFromEveryDirection() {
        NaruModelRegistration r = NaruModelRegistration.of("p", "gemini");
        Assertions.assertThrows(RuntimeException.class, () -> r.with(NaruRegistrationFields.PROVIDER, null));
        Assertions.assertThrows(RuntimeException.class,
                () -> r.toBuilder().put("provider", (NElement) null).build());
        Assertions.assertThrows(RuntimeException.class,
                () -> NaruModelRegistration.of("p", obj()));
    }

    // ── the four names that must never be written as a provider ───────────────────

    @Test
    void aWireProtocolIdOrInternalTypeNamedAsProviderFailsLoudly() {
        for (String bad : List.of("custom", "anthropic", "openapi", "wire")) {
            Assertions.assertThrows(RuntimeException.class,
                    () -> NaruModelRegistration.of("ep", obj("provider", NElement.ofString(bad))),
                    bad + " must be refused, never stored as an identity");
        }
        Assertions.assertThrows(RuntimeException.class,
                () -> NaruModelRegistration.of("ep", obj("url", NElement.ofString("http://x"))),
                "a bare shell with neither identity param fails instead of registering nothing");
    }

    // ── equals describes the identity, not one spelling of it ────────────────────

    @Test
    void twoSpellingsOfOneEndpointAreOneRegistration() {
        NaruModelRegistration canonical = NaruModelRegistration.of("p", obj(
                "provider", NElement.ofString("gemini"),
                "url", NElement.ofString("http://localhost:11434"),
                "apiKey", NElement.ofString("sk-1")));
        NaruModelRegistration aliased = NaruModelRegistration.of("p", obj(
                "provider", NElement.ofString("gemini"),
                "host", NElement.ofString("http://localhost:11434"),
                "api_key", NElement.ofString("sk-1")));
        Assertions.assertEquals(canonical, aliased,
                "key order and alias spelling are storage details, not different endpoints");
        Assertions.assertEquals(canonical.hashCode(), aliased.hashCode());
        Assertions.assertTrue(canonical.isLocal());
    }
}
