package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.expr.NToken;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static net.thevpc.naru.api.model.NaruRegistrationFields.API_KEY;
import static net.thevpc.naru.api.model.NaruRegistrationFields.PROVIDER;

/**
 * A named registration of a provider instance: the provider <b>type</b> that serves it, the
 * <b>instance id</b> it is addressed by, and the parameters it carries (api key, url, pinned model,
 * temperature, contextLength, ...).
 *
 * <p>The parameters a registration is <em>made of</em> are fields, read as {@link #url()},
 * {@link #temperature()}, {@link #models()}. Parameters core has never heard of — a provider
 * extension's own knobs — are {@link #extras()}, which round-trip verbatim and still reach the
 * provider untouched at materialization. Anything that reads a main knob by name, or writes one into
 * a map, is reaching past this type into its storage form; the vocabulary and that boundary live in
 * {@link NaruRegistrationFields}.
 *
 * <p>This is what lets the same type be registered several times: two gemini registrations are two
 * of these, with different ids and different keys, and both are addressed {@code <id>/<model>}.
 *
 * <p>Any <b>string</b> parameter may contain {@code $NAME} / {@code ${NAME}} references — the same
 * interpolation {@code NMsg.ofV} uses. They are resolved when a value is needed
 * ({@link #apiKeyResolved(NaruSession)}, {@link #interpolate(String)}), not when the registration is
 * written, so rotating an exported key needs no re-registration. A literal credential (an
 * {@code apiKey} that references nothing) belongs to the private store and is never printed
 * ({@link #masked()}).
 */
public class NaruModelRegistration implements NToElement {

    /**
     * The internal endpoint type word for a registration that names no provider type. Never stored and
     * never returned by {@link #provider()}: it survives only as the registry key that
     * {@link #endpointType()} answers, because a generic endpoint still has a provider class
     * ({@code NaruCustomProvider}) that speaks its wire.
     */
    public static final String CUSTOM_TYPE = "custom";

    /**
     * Provider values that are not provider types: the internal {@code custom} endpoint type and
     * wire protocol ids with no provider type of their own (only {@code anthropic} today). Written
     * as a provider they would silently rewrite the registration's identity. {@code openapi} and
     * {@code wire} get their own branch because they are pre-rename spellings and deserve a
     * migration hint.
     */
    private static final Set<String> FORBIDDEN_PROVIDERS = Set.of("custom", "anthropic");

    /**
     * The parameters that say <b>which endpoint</b> a registration is, and so whether two
     * registrations of the same id are the same thing. One layer of the store may complete another
     * field by field; it may not mix its key into a different provider or wire protocol, so a
     * layer that rewrites one of these replaces the registration instead of overlaying it.
     */
    public static final Set<String> IDENTITY_PARAMS = Set.of("provider", "protocol");

    private final String id;

    // identity
    private final String provider;
    private final String protocol;
    // endpoint
    private final String url;
    private final String apiKey;
    private final String chatPath;
    // pins
    private final String model;
    private final List<String> models;
    // sampling
    private final Float temperature;
    private final Integer contextLength;
    private final Integer maxTokens;
    private final Float nucleusThreshold;
    private final Integer candidateCount;
    private final List<String> stop;
    // behaviour
    private final NElement thinkingTags;
    private final Boolean tools;
    private final Boolean probe;
    private final Boolean enabled;
    private final Boolean free;
    private final Boolean local;
    // timeouts, as the duration words the wire layer parses
    private final String timeout;
    private final String readTimeout;
    private final String connectTimeout;
    private final String retryPeriod;
    private final Integer maxRetries;
    // what this core version does not declare
    private final Map<String, NElement> extras;
    /**
     * The declared field values, canonical name to value, taken once. {@link #equals(Object)} and
     * {@link #hashCode()} run on this rather than on {@link #storedPairs()} because a store merge
     * compares registrations far more often than it writes them, and rendering the stored form
     * allocates a Nuts element per parameter — which also needs a live workspace. Absence stays
     * absence: a field that is unset is not in this map at all.
     */
    private final Map<String, Object> valueSnapshot;

    private NaruModelRegistration(Builder b) {
        this.id = requireId(b.id);
        String p = NStringUtils.stripToNull(b.provider);
        String pr = NStringUtils.stripToNull(b.protocol);
        if (p == null && pr == null) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid registration '%s': a registration is a provider instance (\"provider\": \"gemini\") "
                            + "or a generic endpoint (\"protocol\": \"openai\", no provider) — it cannot carry neither",
                    id));
        }
        if (p != null) {
            rejectNonProviderType(p, id);
        }
        this.provider = p;
        // the canonical wire id is lowercase: two spellings of one protocol are one endpoint
        this.protocol = pr == null ? null : pr.toLowerCase(Locale.ROOT);
        this.url = b.url;
        this.apiKey = b.apiKey;
        this.chatPath = b.chatPath;
        this.model = b.model;
        this.models = b.models;
        this.temperature = b.temperature;
        this.contextLength = b.contextLength;
        this.maxTokens = b.maxTokens;
        this.nucleusThreshold = b.nucleusThreshold;
        this.candidateCount = b.candidateCount;
        this.stop = b.stop;
        this.thinkingTags = b.thinkingTags;
        this.tools = b.tools;
        this.probe = b.probe;
        this.enabled = b.enabled;
        this.free = b.free;
        this.local = b.local;
        this.timeout = b.timeout;
        this.readTimeout = b.readTimeout;
        this.connectTimeout = b.connectTimeout;
        this.retryPeriod = b.retryPeriod;
        this.maxRetries = b.maxRetries;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
        this.valueSnapshot = Collections.unmodifiableMap(buildValueSnapshot());
    }

    private Map<String, Object> buildValueSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            Object v = f.get(this);
            if (v != null) {
                out.put(f.key(), v);
            }
        }
        return out;
    }

    private static String requireId(String id) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing registration id"));
        }
        return iid;
    }

    private static void rejectNonProviderType(String provider, String id) {
        String lower = provider.toLowerCase(Locale.ROOT);
        if (lower.equals("openapi")) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid provider '%s' in registration '%s': 'openai' is now both a wire id and a built-in "
                            + "provider type — write provider: \"openai\" for OpenAI's API itself, or "
                            + "protocol: \"openai\" with no provider for a generic endpoint",
                    provider, id));
        }
        if (lower.equals("wire")) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid provider '%s' in registration '%s': it is the pre-rename spelling of the internal "
                            + "custom endpoint type — a generic endpoint is written protocol: \"openai\" "
                            + "with no provider",
                    provider, id));
        }
        if (lower.equals("custom")) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid provider '%s' in registration '%s': it is the internal custom endpoint type — "
                            + "a generic endpoint is written protocol: \"openai\" with no provider",
                    provider, id));
        }
        if (FORBIDDEN_PROVIDERS.contains(lower)) {
            throw new NIllegalArgumentException(NMsg.ofC(
                    "invalid provider '%s' in registration '%s': it is a wire protocol id, not a provider "
                            + "type — a generic endpoint is written protocol: \"%s\" with no provider",
                    provider, id, lower));
        }
    }

    // ── authoring ─────────────────────────────────────────────────────────────────

    /**
     * A provider-instance registration carrying only its type. Add the key, the pinned model and any
     * sampling parameter with {@link #toBuilder()} — or, for a command line that has a name and an
     * element per parameter, {@link Builder#put(String, NElement)}, which routes each one through the
     * vocabulary so aliases fold and kinds are checked.
     */
    public static NaruModelRegistration of(String id, String provider) {
        if (NStringUtils.stripToNull(provider) == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing 'provider' in registration '%s'",
                    id == null ? "?" : id));
        }
        return builder(id).provider(provider).build();
    }

    /**
     * Reads a registration from its stored object ({@code {provider: "...", ...}} for a provider
     * instance, or {@code {protocol: "openai", url: ..., ...}} with no provider for a generic
     * endpoint). Aliases fold onto the canonical field, values are read leniently by kind, and names
     * this version does not declare go to {@link #extras()} rather than being refused — a newer file
     * stays readable.
     *
     * @throws NIllegalArgumentException when the element is not an object, names neither a provider
     *                                   nor a protocol, or names a wire protocol id as its provider:
     *                                   a hand-edited file must fail loudly, never silently register
     *                                   nothing
     */
    public static NaruModelRegistration of(String id, NElement element) {
        if (element == null || !element.isListContainer()) {
            throw new NIllegalArgumentException(NMsg.ofC("invalid registration '%s' : expected an object", id));
        }
        Builder b = new Builder(id);
        for (NPairElement p : element.asListContainer().get().namedPairs()) {
            String k = p.key().asStringValue().orNull();
            if (k != null) {
                b.put(k, p.value());
            }
        }
        return b.build();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public Builder toBuilder() {
        Builder b = new Builder(id);
        b.provider = provider;
        b.protocol = protocol;
        b.url = url;
        b.apiKey = apiKey;
        b.chatPath = chatPath;
        b.model = model;
        b.models = models;
        b.temperature = temperature;
        b.contextLength = contextLength;
        b.maxTokens = maxTokens;
        b.nucleusThreshold = nucleusThreshold;
        b.candidateCount = candidateCount;
        b.stop = stop;
        b.thinkingTags = thinkingTags;
        b.tools = tools;
        b.probe = probe;
        b.enabled = enabled;
        b.free = free;
        b.local = local;
        b.timeout = timeout;
        b.readTimeout = readTimeout;
        b.connectTimeout = connectTimeout;
        b.retryPeriod = retryPeriod;
        b.maxRetries = maxRetries;
        b.extras.putAll(extras);
        // every value this registration carries is canonical — storedPairs() only ever reports
        // canonical names — so rebuilding it must not let an alias write displace one
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            if (f.get(this) != null) {
                b.canonicalWrites.add(f);
            }
        }
        return b;
    }

    /** The vocabulary: file order is declaration order, and so is a form's field order. */
    public static List<NaruRegistrationFields.Field<?>> fields() {
        return NaruRegistrationFields.fields();
    }

    /** The field a parameter name, alias or casing resolves to; null when core does not declare it. */
    public static NaruRegistrationFields.Field<?> field(String anyName) {
        return NaruRegistrationFields.find(anyName);
    }

    // ── identity ──────────────────────────────────────────────────────────────────

    public String id() {
        return id;
    }

    /**
     * The provider type this instance serves ({@code gemini}, {@code openai}, ...), or empty when it
     * names none — a generic endpoint selected with {@code --protocol} alone. {@code custom} is never
     * stored as a provider value and is no longer answered in its place: a caller that means "does
     * this have a type" asks {@link #isGeneric()}, and one that means "which class serves it" asks
     * {@link #endpointType()}.
     */
    public NOptional<String> provider() {
        return NOptional.of(provider);
    }

    /** True for an endpoint with no provider type behind it — its protocol <b>is</b> its identity. */
    public boolean isGeneric() {
        return provider == null;
    }

    /**
     * The word the registry addresses this endpoint's provider class by: the declared provider type,
     * or the internal {@code custom} type for a generic endpoint. This is the only place that word
     * still appears, because it is a <b>registry key</b> and not a parameter — nothing stores it, no
     * file shows it, and {@link #equals(Object)} does not count it.
     */
    public String endpointType() {
        return provider == null ? CUSTOM_TYPE : provider;
    }

    /**
     * The wire protocol this instance speaks, or empty to keep the provider type's own wire shape.
     */
    public NOptional<String> protocol() {
        return NOptional.of(protocol);
    }

    // ── endpoint ──────────────────────────────────────────────────────────────────

    public NOptional<String> url() {
        return NOptional.of(url);
    }

    /** The key as stored, which may be a {@code $NAME} reference rather than a value. */
    public NOptional<String> apiKey() {
        return NOptional.of(apiKey);
    }

    /**
     * The key with {@code $NAME}/{@code ${NAME}} resolved through the layered env — the instance's
     * own value winning, then the environment. Empty when unset or when the reference names an unset
     * variable, which is how a registration "finds its key by itself" without re-registration.
     */
    public NOptional<String> apiKeyResolved(NaruSession session) {
        return apiKey(session == null ? null : envResolver(session));
    }

    private NOptional<String> apiKey(Function<String, String> resolver) {
        if (apiKey == null) {
            return NOptional.ofEmpty();
        }
        return interpolate(apiKey, resolver);
    }

    public NOptional<String> chatPath() {
        return NOptional.of(chatPath);
    }

    // ── pins ──────────────────────────────────────────────────────────────────────

    public NOptional<String> model() {
        return NOptional.of(model);
    }

    /** The {@code models} list as declared; empty when the parameter is absent or names nothing. */
    public List<String> models() {
        return models == null ? List.of() : models;
    }

    /** Every pin, from both spellings — what {@code /model use <id>} counts before picking. */
    public List<String> allPins() {
        List<String> out = new ArrayList<>();
        if (model != null) {
            out.add(model);
        }
        if (models != null) {
            for (String m : models) {
                if (!out.contains(m)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ── sampling ──────────────────────────────────────────────────────────────────

    public NOptional<Float> temperature() {
        return NOptional.of(temperature);
    }

    public NOptional<Integer> contextLength() {
        return NOptional.of(contextLength);
    }

    public NOptional<Integer> maxTokens() {
        return NOptional.of(maxTokens);
    }

    public NOptional<Float> nucleusThreshold() {
        return NOptional.of(nucleusThreshold);
    }

    public NOptional<Integer> candidateCount() {
        return NOptional.of(candidateCount);
    }

    public List<String> stop() {
        return stop == null ? List.of() : stop;
    }

    // ── behaviour ─────────────────────────────────────────────────────────────────

    /** The declared flag, empty when unset: unset is not false, it is the provider's own default. */
    public NOptional<Boolean> enabled() {
        return NOptional.of(enabled);
    }

    public NOptional<Boolean> tools() {
        return NOptional.of(tools);
    }

    public NOptional<Boolean> probe() {
        return NOptional.of(probe);
    }

    /** The declared {@code free} flag alone — {@link #isFree()} adds the local-by-construction rule. */
    public NOptional<Boolean> free() {
        return NOptional.of(free);
    }

    /** The declared {@code local} flag alone — {@link #isLocal()} adds the provider and url rules. */
    public NOptional<Boolean> local() {
        return NOptional.of(local);
    }

    /**
     * Whether this registration declares itself free ({@code free=true}). A registration that does
     * not say is assumed paid, matching the capability default. Local providers are free by
     * construction regardless.
     */
    public boolean isFree() {
        if (Boolean.TRUE.equals(free)) {
            return true;
        }
        return NaruModelFilter.isConventionallyLocal(endpointType());
    }

    /**
     * Whether this registration points at a local endpoint: an explicit {@code local=true}, a
     * conventionally local provider type (ollama, llama.cpp, colibri), or a loopback url. Aliases
     * folded at parse time, so {@code host} and {@code baseUrl} spellings count here as the one
     * endpoint they always were.
     */
    public boolean isLocal() {
        if (local != null) {
            return local;
        }
        if (NaruModelFilter.isConventionallyLocal(endpointType())) {
            return true;
        }
        if (url != null) {
            String x = url.toLowerCase(Locale.ROOT);
            return x.contains("localhost") || x.contains("127.0.0.1") || x.contains("::1");
        }
        return false;
    }

    /**
     * The {@code thinkingTags} parameter as written — an {@code {openTag, closeTag}} object or an
     * {@code open,close} list. Kept in its stored shape on purpose: both spellings mean one tag pair
     * and only {@link NaruThinkingTags#of(String, String)} decides how to read them, so this type
     * would rather hand the element over than guess and lose half a pair.
     */
    public NOptional<NElement> thinkingTags() {
        return NOptional.of(thinkingTags);
    }

    // ── timeouts ──────────────────────────────────────────────────────────────────

    /** Duration words as stored ({@code "120s"}); parsing belongs to {@link NaruModelTimeouts}. */
    public NOptional<String> timeout() {
        return NOptional.of(timeout);
    }

    public NOptional<String> readTimeout() {
        return NOptional.of(readTimeout);
    }

    public NOptional<String> connectTimeout() {
        return NOptional.of(connectTimeout);
    }

    public NOptional<String> retryPeriod() {
        return NOptional.of(retryPeriod);
    }

    public NOptional<Integer> maxRetries() {
        return NOptional.of(maxRetries);
    }

    // ── the open remainder ────────────────────────────────────────────────────────

    /**
     * Parameters core does not declare, as stored. A provider extension's knobs arrive here and are
     * forwarded verbatim at materialization; reading one is deliberately a different call from
     * reading a field, so a typo cannot pass as a parameter of this class.
     */
    public Map<String, NElement> extras() {
        return extras;
    }

    public NOptional<NElement> extra(String key) {
        return NOptional.of(key == null ? null : extras.get(key));
    }

    // ── the storage boundary ──────────────────────────────────────────────────────

    /**
     * The registration as it is stored, as pairs in file order: every declared field that carries a
     * value, then the undeclared ones exactly as they were read. {@link #toElement()} writes this,
     * the store splits it between its public and private files, and materialization forwards it to
     * the provider — three jobs a parameter map used to do. None of them needed a mutable bag of
     * parameters; they needed the order, the canonical names and the secret flag, which are the
     * vocabulary's, not the map's.
     *
     * <p>Names here are canonical: a file that spelled its endpoint {@code host} reads as {@code url}.
     */
    public List<NPairElement> storedPairs() {
        return storedElement().asListContainer().get().namedPairs();
    }

    private NElement storedElement() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            NElement e = elementOf(f);
            if (e != null) {
                b.set(f.key(), e);
            }
        }
        for (Map.Entry<String, NElement> e : extras.entrySet()) {
            b.set(e.getKey(), e.getValue());
        }
        return b.build();
    }

    /** The stored form of one field, or null when it is unset. */
    private <T> NElement elementOf(NaruRegistrationFields.Field<T> f) {
        return f.render(f.get(this));
    }

    /**
     * An <b>undeclared</b> parameter read as one of its kinds. Declared parameters have their
     * accessor; this is the typed door onto {@link #extras()} for a host — a provider extension's own
     * knob — that has to turn a stored element into a number or a flag without the caller repeating
     * the lenient-parse rules. Empty when the key is absent or holds something of another kind.
     */
    public <T> NOptional<T> extra(String key, NaruRegistrationFields.Field<T> as) {
        NElement e = key == null ? null : extras.get(key);
        if (e == null || e.isNull()) {
            return NOptional.ofEmpty();
        }
        T v = as.parse(e);
        return NOptional.of(v);
    }

    /**
     * Copy with one <b>declared</b> field set (a null value clears it).
     */
    public <T> NaruModelRegistration with(NaruRegistrationFields.Field<T> f, T value) {
        NaruModelRegistration.Builder b = toBuilder();
        if (f == PROVIDER && value == null) {
            throw new NIllegalArgumentException(NMsg.ofC("cannot remove the registration's provider"));
        }
        f.set(b, value);
        return b.build();
    }

    /**
     * Copy with one <b>undeclared</b> parameter set (a null value removes it).
     */
    public NaruModelRegistration withExtra(String key, NElement value) {
        Builder b = toBuilder();
        b.extras(key, value);
        return b.build();
    }

    /**
     * Copy with every literal credential masked ({@code sk-***1234}) — what listings and logs must
     * show. {@code $NAME} references are kept as-is: they name a variable, not a value.
     */
    public NaruModelRegistration masked() {
        Builder b = toBuilder();
        if (isSecretLiteral(API_KEY.key(), apiKey == null ? null : NElement.ofString(apiKey))) {
            b.apiKey(mask(apiKey));
        }
        Map<String, NElement> keep = new LinkedHashMap<>();
        for (Map.Entry<String, NElement> e : extras.entrySet()) {
            if (isSecretLiteral(e.getKey(), e.getValue())) {
                keep.put(e.getKey(), NElement.ofString(mask(e.getValue().asStringValue().get())));
            } else {
                keep.put(e.getKey(), e.getValue());
            }
        }
        b.extras.clear();
        b.extras.putAll(keep);
        return b.build();
    }

    // ── secrets ───────────────────────────────────────────────────────────────────

    /**
     * The parameter name holds a credential. Every spelling that used to be name-matched
     * ({@code apiKey}, {@code apikey}, {@code api_key}, {@code key}) now resolves to the declared
     * {@code apiKey} field through {@link NaruRegistrationFields#find(String)}, so the answer comes
     * from the vocabulary and the old secret-name set is gone with its two disagreements: {@code key}
     * is no longer filed private by one layer and printed whole by another, and {@code api_key} is no
     * longer filed private and then never read.
     *
     * <p>An <b>undeclared</b> name answers false. That is unchanged from before — the old set only
     * ever held those four spellings — and it is the gap phase 3 of the plan closes by letting a
     * provider declare its own knobs ({@code paramDeclarations()}) instead of core guessing by name.
     */
    public static boolean isSecretParam(String name) {
        NaruRegistrationFields.Field<?> f = NaruRegistrationFields.find(name);
        return f != null && f.isSecret();
    }

    /**
     * The parameter is a <b>literal</b> credential: a secret parameter whose value references no
     * variable. Such a value must be masked when printed and stored in the private file; a
     * {@code $NAME} value references nothing secret and stays public.
     */
    public static boolean isSecretLiteral(String name, NElement value) {
        if (!isSecretParam(name) || value == null || !value.isAnyStringOrName()) {
            return false;
        }
        String s = value.asStringValue().orNull();
        return s != null && !isInterpolated(s);
    }

    /**
     * This registration's own credential as a literal, before interpolation. A value that names a
     * variable is not a literal and is neither masked nor filed privately.
     */
    public boolean hasLiteralApiKey() {
        return isSecretLiteral(API_KEY.key(), apiKey == null ? null : NElement.ofString(apiKey));
    }

    // ── interpolation ─────────────────────────────────────────────────────────────

    /**
     * The value contains at least one {@code $NAME} / {@code ${NAME}} reference.
     */
    public static boolean isInterpolated(String value) {
        if (value == null || value.indexOf('$') < 0) {
            return false;
        }
        return NStringUtils.parseDollarPlaceHolder(value).anyMatch(t ->
                t.ttype == NToken.TT_DOLLAR || t.ttype == NToken.TT_DOLLAR_BRACE);
    }

    /**
     * Resolves {@code $NAME}/{@code ${NAME}} references from the environment.
     *
     * @see #interpolate(String, Function)
     */
    public static NOptional<String> interpolate(String value) {
        return interpolate(value, System::getenv);
    }

    /**
     * Resolves {@code $NAME}/{@code ${NAME}} references with the given resolver. A reference to an
     * unset variable empties the <b>whole</b> value (empty, not the literal {@code $NAME}): the
     * caller must fall through to the next config step rather than send {@code $GEMINI_KEY_A} as an
     * api key. A {@code $} that starts no name ({@code "100$"}, trailing {@code $}) is literal.
     */
    public static NOptional<String> interpolate(String value, Function<String, String> resolver) {
        if (value == null) {
            return NOptional.ofEmpty();
        }
        if (!isInterpolated(value)) {
            return NOptional.of(value);
        }
        boolean[] missing = {false};
        String resolved = NStringUtils.replaceDollarPlaceHolder(value, n -> {
            if (n == null || n.isBlank()) {
                return null;    // bare '$': literal, not a reference
            }
            String v = resolver == null ? null : resolver.apply(n);
            if (v == null) {
                missing[0] = true;
            }
            return v;
        });
        if (missing[0]) {
            return NOptional.ofNamedEmpty(NMsg.ofC("unset variable reference"));
        }
        return NOptional.of(resolved);
    }

    /**
     * The layered resolver config resolution asks for: session env first, then agent env, then the
     * system environment — the most specific scope wins. Given to
     * {@link #interpolate(String, Function)} it makes a {@code $NAME} reference follow the same
     * precedence as a direct name lookup ({@link #envValue(NaruSession, String)}), so rotating a key
     * in any layer needs no re-registration.
     *
     * @param session whose envs are consulted; null means system env only
     */
    public static Function<String, String> envResolver(NaruSession session) {
        return name -> envValue(session, name);
    }

    /**
     * The value of {@code name} from the first layer that defines it — session env, then agent env,
     * then the system environment — or null when no layer does. A blank value never carries a key:
     * it counts as unset and the search continues, which is how an emptied layer hands the lookup to
     * the next one instead of locking it out.
     */
    public static String envValue(NaruSession session, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (session != null) {
            NOptional<Object> s = session.getSessionEnv(name);
            String sv = envString(s == null ? null : s.orNull());
            if (sv != null && !sv.isBlank()) {
                return NStringUtils.strip(sv);
            }
            NaruEnv agentEnv = session.agent() == null ? null : session.agent().env();
            if (agentEnv != null) {
                NOptional<NElement> a = agentEnv.get(name);
                String av = a == null ? null : a.flatMap(NElement::asStringValue).orNull();
                if (av != null && !av.isBlank()) {
                    return NStringUtils.strip(av);
                }
            }
        }
        return NStringUtils.stripToNull(System.getenv(name));
    }

    private static String envString(Object v) {
        if (v instanceof NElement) {
            return ((NElement) v).asStringValue().orNull();
        }
        return v == null ? null : String.valueOf(v);
    }

    private static String mask(String v) {
        if (v.length() <= 4) {
            return "***";
        }
        return "sk-***" + v.substring(v.length() - 4);
    }

    // ── the storage boundary ──────────────────────────────────────────────────────

    /**
     * The object as stored: declared parameters in vocabulary order, then the extras. Writing an
     * unset field would put a {@code null} in the file, and a file is read as a delta over the
     * provider's defaults — so absence is a missing key, never a null value.
     */
    @Override
    public NElement toElement() {
        return storedElement();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelRegistration that = (NaruModelRegistration) o;
        // two spellings of one endpoint are one registration: the snapshot holds canonical field
        // values, so an alias-keyed file and a canonically-keyed one compare equal
        return id.equals(that.id) && valueSnapshot.equals(that.valueSnapshot) && extras.equals(that.extras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, valueSnapshot, extras);
    }

    /**
     * Id followed by the <b>masked</b> parameters: safe to log or print. The declared fields print
     * from their values, not from their stored elements, so a log line never needs a workspace; the
     * extras print as the elements they were read as.
     */
    @Override
    @SuppressWarnings("unchecked")
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('{');
        boolean first = true;
        for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
            Object v = f.get(this);
            if (v == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(f.key()).append('=');
            if (f.isSecret() && !isInterpolated(String.valueOf(v))) {
                sb.append(mask(String.valueOf(v)));
            } else {
                sb.append(v instanceof List ? String.join(",", (List<String>) v) : String.valueOf(v));
            }
        }
        for (Map.Entry<String, NElement> e : extras.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            NElement v = e.getValue();
            sb.append(e.getKey()).append('=').append(v.isAnyStringOrName() ? v.asStringValue().get() : v.toString());
        }
        return sb.append('}').toString();
    }

    /**
     * Mutable authoring form. Every setter takes the boxed value and a null clears, so "unset" is
     * representable — the distinction the wire layer depends on for {@code enabled} and the
     * sampling knobs.
     */
    public static final class Builder {
        private final String id;
        private String provider;
        private String protocol;
        private String url;
        private String apiKey;
        private String chatPath;
        private String model;
        private List<String> models;
        private Float temperature;
        private Integer contextLength;
        private Integer maxTokens;
        private Float nucleusThreshold;
        private Integer candidateCount;
        private List<String> stop;
        private NElement thinkingTags;
        private Boolean tools;
        private Boolean probe;
        private Boolean enabled;
        private Boolean free;
        private Boolean local;
        private String timeout;
        private String readTimeout;
        private String connectTimeout;
        private String retryPeriod;
        private Integer maxRetries;
        private final Map<String, NElement> extras = new LinkedHashMap<>();
        /**
         * Fields already written under their canonical name. A write that arrives through an alias
         * ({@code api_key}, {@code host}) may not displace one that arrived canonically: a file pair
         * that names the same field twice would otherwise decide the value by which file it read
         * last. See {@link Builder#put(String, NElement)}.
         */
        private final Set<NaruRegistrationFields.Field<?>> canonicalWrites = new HashSet<>();

        Builder(String id) {
            this.id = id;
        }

        public Builder provider(String v) {
            this.provider = v;
            return this;
        }

        public Builder protocol(String v) {
            this.protocol = v;
            return this;
        }

        public Builder url(String v) {
            this.url = v;
            return this;
        }

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        public Builder chatPath(String v) {
            this.chatPath = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder models(List<String> v) {
            this.models = v == null ? null : Collections.unmodifiableList(new ArrayList<>(v));
            return this;
        }

        public Builder temperature(Float v) {
            this.temperature = v;
            return this;
        }

        public Builder contextLength(Integer v) {
            this.contextLength = v;
            return this;
        }

        public Builder maxTokens(Integer v) {
            this.maxTokens = v;
            return this;
        }

        public Builder nucleusThreshold(Float v) {
            this.nucleusThreshold = v;
            return this;
        }

        public Builder candidateCount(Integer v) {
            this.candidateCount = v;
            return this;
        }

        public Builder stop(List<String> v) {
            this.stop = v == null ? null : Collections.unmodifiableList(new ArrayList<>(v));
            return this;
        }

        public Builder thinkingTags(NElement v) {
            this.thinkingTags = v;
            return this;
        }

        public Builder tools(Boolean v) {
            this.tools = v;
            return this;
        }

        public Builder probe(Boolean v) {
            this.probe = v;
            return this;
        }

        public Builder enabled(Boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder free(Boolean v) {
            this.free = v;
            return this;
        }

        public Builder local(Boolean v) {
            this.local = v;
            return this;
        }

        public Builder timeout(String v) {
            this.timeout = v;
            return this;
        }

        public Builder readTimeout(String v) {
            this.readTimeout = v;
            return this;
        }

        public Builder connectTimeout(String v) {
            this.connectTimeout = v;
            return this;
        }

        public Builder retryPeriod(String v) {
            this.retryPeriod = v;
            return this;
        }

        public Builder maxRetries(Integer v) {
            this.maxRetries = v;
            return this;
        }

        /** Set or clear (null) one undeclared parameter. */
        public Builder extras(String key, NElement value) {
            if (key == null) {
                return this;
            }
            if (value == null || value.isNull()) {
                extras.remove(key);
            } else {
                extras.put(key, value);
            }
            return this;
        }

        /**
         * The identity typed so far, for a validator that must answer questions about a
         * half-built registration — {@code /model update} checks the provider and the protocol
         * before it commits, because its message has to name the knob to turn and the constructor's
         * does not know the command line.
         */
        public NOptional<String> providerValue() {
            return NOptional.of(NStringUtils.stripToNull(provider));
        }

        public NOptional<String> protocolValue() {
            return NOptional.of(NStringUtils.stripToNull(protocol));
        }

        /**
         * Route one stored pair to its field or to extras: the alias folds, the kind is checked, and
         * a name core does not declare is kept rather than refused.
         *
         * <p>Two spellings of one field in one load are a conflict, and it is resolved by spelling
         * rather than by order: a canonical write claims the field and a later alias cannot displace
         * it, so {@code apiKey: $VAR} in a public file survives {@code api_key: sk-…} beside it. Two
         * aliases, or two canonicals, keep the usual last-write-wins.
         */
        @SuppressWarnings("unchecked")
        public Builder put(String name, NElement value) {
            if (name == null) {
                return this;
            }
            NaruRegistrationFields.Field field = NaruRegistrationFields.find(name);
            if (field == null) {
                return extras(name, value);
            }
            boolean canonical = field.key().equalsIgnoreCase(NStringUtils.strip(name));
            if (!canonical && canonicalWrites.contains(field)) {
                return this;
            }
            if (canonical) {
                canonicalWrites.add(field);
            }
            return setRaw(field, value);
        }

        @SuppressWarnings("unchecked")
        private <T> Builder setRaw(NaruRegistrationFields.Field<T> field, NElement value) {
            field.set(this, value == null || value.isNull() ? null : field.parse(value));
            return this;
        }

        public NaruModelRegistration build() {
            return new NaruModelRegistration(this);
        }
    }

    // ── the field reads the vocabulary needs (package-private: no second accessor set) ──

    String rawProvider() {
        return provider;
    }

    String rawProtocol() {
        return protocol;
    }

    String rawUrl() {
        return url;
    }

    String rawApiKey() {
        return apiKey;
    }

    String rawChatPath() {
        return chatPath;
    }

    String rawModel() {
        return model;
    }

    List<String> rawModels() {
        return models;
    }

    Float rawTemperature() {
        return temperature;
    }

    Integer rawContextLength() {
        return contextLength;
    }

    Integer rawMaxTokens() {
        return maxTokens;
    }

    Float rawNucleusThreshold() {
        return nucleusThreshold;
    }

    Integer rawCandidateCount() {
        return candidateCount;
    }

    List<String> rawStop() {
        return stop;
    }

    NElement rawThinkingTags() {
        return thinkingTags;
    }

    Boolean rawTools() {
        return tools;
    }

    Boolean rawProbe() {
        return probe;
    }

    Boolean rawEnabled() {
        return enabled;
    }

    Boolean rawFree() {
        return free;
    }

    Boolean rawLocal() {
        return local;
    }

    String rawTimeout() {
        return timeout;
    }

    String rawReadTimeout() {
        return readTimeout;
    }

    String rawConnectTimeout() {
        return connectTimeout;
    }

    String rawRetryPeriod() {
        return retryPeriod;
    }

    Integer rawMaxRetries() {
        return maxRetries;
    }
}
