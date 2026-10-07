package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.expr.NToken;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A named registration of a provider instance: the provider <b>type</b> that
 * serves it, the <b>instance id</b> it is addressed by, and the parameters it
 * carries (api key, url, pinned model, temperature, contextLength, ...).
 *
 * <p>This is what lets the same type be registered several times: two gemini
 * registrations are two of these, with different ids and different keys, and both
 * are addressed {@code <id>/<model>}.
 *
 * <p>Any <b>string</b> parameter may contain {@code $NAME} / {@code ${NAME}}
 * references — the same interpolation {@code NMsg.ofV} uses. They are resolved
 * when a value is needed ({@link #interpolate(String)}), not when the
 * registration is written, so rotating an exported key needs no re-registration.
 * A literal credential (an {@code apiKey} that references nothing) belongs to the
 * private store and is never printed ({@link #masked()}).
 */
public class NaruModelRegistration implements NToElement {

    /**
     * Parameter names that hold a credential when their value is a literal.
     * Matches the names the wire layer probes for keys ({@code apiKey},
     * {@code api_key}, {@code key}).
     */
    private static final Set<String> SECRET_PARAMS = Set.of("apikey", "api_key", "key");

    /**
     * Provider values that are wire-id shorthands: {@code openapi} or
     * {@code anthropic} written as the provider mean the generic {@code wire}
     * provider speaking that protocol (design doc §8). {@code gemini} is
     * deliberately absent — as a provider it means the built-in gemini provider;
     * its native wire shape is selected with {@code --protocol=gemini}.
     */
    private static final Set<String> WIRE_SHORTHANDS = Set.of("openapi", "anthropic");

    private final String id;
    private final Map<String, NElement> params;

    private NaruModelRegistration(String id, Map<String, NElement> params) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing registration id"));
        }
        this.id = iid;
        this.params = normalize(iid, params);
    }

    /**
     * Validates and normalizes a parameter map: {@code provider} is required, and
     * wire-id shorthands are expanded to {@code wire} + matching {@code protocol}.
     */
    private static Map<String, NElement> normalize(String id, Map<String, NElement> params) {
        NElement p = params == null ? null : params.get("provider");
        String provider = p == null ? null : NStringUtils.stripToNull(p.asStringValue().orNull());
        if (provider == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing 'provider' in registration '%s'", id));
        }
        Map<String, NElement> m = new LinkedHashMap<>(params == null ? Map.of() : params);
        String lower = provider.toLowerCase();
        if (WIRE_SHORTHANDS.contains(lower)) {
            m.put("provider", NElement.ofString("wire"));
            if (!m.containsKey("protocol")) {
                m.put("protocol", NElement.ofString(lower));
            }
        } else {
            m.put("provider", NElement.ofString(provider));
        }
        return m;
    }

    public static NaruModelRegistration of(String id, String provider) {
        if (NStringUtils.stripToNull(provider) == null) {
            throw new NIllegalArgumentException(NMsg.ofC("missing 'provider' in registration '%s'",
                    id == null ? "?" : id));
        }
        Map<String, NElement> m = new LinkedHashMap<>();
        m.put("provider", NElement.ofString(provider));
        return new NaruModelRegistration(id, m);
    }

    /**
     * Reads a registration from its stored object ({@code {provider: "...", ...}}).
     *
     * @throws NIllegalArgumentException when the element is not an object or has no
     *                                   provider: a hand-edited file must fail loudly,
     *                                   never silently register nothing
     */
    public static NaruModelRegistration of(String id, NElement element) {
        if (element != null && element.isListContainer()) {
            Map<String, NElement> m = new LinkedHashMap<>();
            for (NPairElement p : element.asListContainer().get().namedPairs()) {
                String k = p.key().asStringValue().orNull();
                if (k != null) {
                    m.put(k, p.value());
                }
            }
            return new NaruModelRegistration(id, m);
        }
        throw new NIllegalArgumentException(NMsg.ofC("invalid registration '%s' : expected an object", id));
    }

    public static NaruModelRegistration of(String id, Map<String, NElement> params) {
        return new NaruModelRegistration(id, params);
    }

    public String id() {
        return id;
    }

    /**
     * The provider type this instance serves ({@code gemini}, {@code ollama},
     * {@code wire}, ...). Never a wire shorthand: those are normalized at parse
     * time into {@code wire} + {@link #protocol()}.
     */
    public String provider() {
        return params.get("provider").asStringValue().orNull();
    }

    /**
     * The wire protocol this instance speaks, or empty to keep the provider
     * type's own wire shape.
     */
    public NOptional<String> protocol() {
        return stringValue("protocol");
    }

    /**
     * All parameters as stored (including {@code provider}), insertion ordered.
     */
    public Map<String, NElement> params() {
        return Collections.unmodifiableMap(params);
    }

    public NOptional<NElement> param(String name) {
        return NOptional.of(name == null ? null : params.get(name));
    }

    /**
     * The parameter's raw string value, no interpolation.
     */
    public NOptional<String> stringValue(String name) {
        NElement e = name == null ? null : params.get(name);
        return e == null ? NOptional.ofEmpty() : e.asStringValue();
    }

    /**
     * The parameter's string value with {@code $NAME}/{@code ${NAME}} resolved from
     * the environment. Empty when the parameter is absent <b>or</b> references an
     * unset variable: an unresolvable reference is not a value, so the caller falls
     * through to the next config step ({@code <id>.<param>} env key, then the
     * provider default).
     */
    public NOptional<String> resolve(String name) {
        return stringValue(name).flatMap(NaruModelRegistration::interpolate);
    }

    public NOptional<Float> floatValue(String name) {
        NElement e = name == null ? null : params.get(name);
        return e == null ? NOptional.ofEmpty() : e.asFloatValue();
    }

    public NOptional<Integer> intValue(String name) {
        NElement e = name == null ? null : params.get(name);
        return e == null ? NOptional.ofEmpty() : e.asIntValue();
    }

    public NOptional<Long> longValue(String name) {
        NElement e = name == null ? null : params.get(name);
        return e == null ? NOptional.ofEmpty() : e.asLongValue();
    }

    public NOptional<Boolean> booleanValue(String name) {
        NElement e = name == null ? null : params.get(name);
        return e == null ? NOptional.ofEmpty() : e.asBooleanValue();
    }

    /**
     * A list-valued parameter: an array as stored, or a comma-separated string.
     * Used for {@code model}/{@code models}, {@code stop}, {@code thinkingTags}.
     */
    public List<String> stringList(String name) {
        NElement e = name == null ? null : params.get(name);
        if (e == null) {
            return List.of();
        }
        if (e.isArray()) {
            return e.asArray().map(a -> a.children().stream()
                    .map(y -> y.asStringValue().orNull())
                    .filter(y -> y != null && !y.isBlank())
                    .collect(Collectors.toList())).orElse(List.of());
        }
        String s = e.asStringValue().orNull();
        if (NBlankable.isBlank(s)) {
            return List.of();
        }
        return List.of(s.split(",")).stream()
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Copy with one parameter set ({@code null} value removes it).
     */
    public NaruModelRegistration withParam(String name, NElement value) {
        if (name == null) {
            return this;
        }
        Map<String, NElement> m = new LinkedHashMap<>(params);
        if (value == null) {
            m.remove(name);
        } else {
            m.put(name, value);
        }
        return new NaruModelRegistration(id, m);
    }

    public NaruModelRegistration withParam(String name, String value) {
        return withParam(name, value == null ? null : NElement.ofString(value));
    }

    public NaruModelRegistration withoutParam(String name) {
        return withParam(name, (NElement) null);
    }

    /**
     * Copy with every literal credential masked ({@code sk-***1234}) — what
     * listings and logs must show. {@code $NAME} references are kept as-is: they
     * name a variable, not a value.
     */
    public NaruModelRegistration masked() {
        Map<String, NElement> m = new LinkedHashMap<>();
        for (Map.Entry<String, NElement> e : params.entrySet()) {
            if (isSecretLiteral(e.getKey(), e.getValue())) {
                m.put(e.getKey(), NElement.ofString(mask(e.getValue().asStringValue().get())));
            } else {
                m.put(e.getKey(), e.getValue());
            }
        }
        return new NaruModelRegistration(id, m);
    }

    /**
     * The parameter name holds a credential ({@code apiKey}, {@code api_key},
     * {@code key}) — case-insensitive.
     */
    public static boolean isSecretParam(String name) {
        return name != null && SECRET_PARAMS.contains(name.trim().toLowerCase());
    }

    /**
     * The parameter is a <b>literal</b> credential: a secret param whose value
     * references no variable. Such a value must be masked when printed and stored
     * in the private file; a {@code $NAME} value references nothing secret and
     * stays public.
     */
    public static boolean isSecretLiteral(String name, NElement value) {
        if (!isSecretParam(name) || value == null || !value.isAnyStringOrName()) {
            return false;
        }
        String s = value.asStringValue().orNull();
        return s != null && !isInterpolated(s);
    }

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
     * Resolves {@code $NAME}/{@code ${NAME}} references with the given resolver.
     * A reference to an unset variable empties the <b>whole</b> value (empty, not
     * the literal {@code $NAME}): the caller must fall through to the next config
     * step rather than send {@code $GEMINI_KEY_A} as an api key. A {@code $} that
     * starts no name ({@code "100$"}, trailing {@code $}) is literal.
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
            if (NBlankable.isBlank(n)) {
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

    private static String mask(String v) {
        if (v.length() <= 4) {
            return "***";
        }
        return "sk-***" + v.substring(v.length() - 4);
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder b = NElement.ofObjectBuilder();
        for (Map.Entry<String, NElement> e : params.entrySet()) {
            b.set(e.getKey(), e.getValue());
        }
        return b.build();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruModelRegistration that = (NaruModelRegistration) o;
        return id.equals(that.id) && params.equals(that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, params);
    }

    /**
     * Id followed by the <b>masked</b> parameters: safe to log or print.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('{');
        boolean first = true;
        for (Map.Entry<String, NElement> e : params.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(e.getKey()).append('=');
            if (isSecretLiteral(e.getKey(), e.getValue())) {
                sb.append(mask(e.getValue().asStringValue().get()));
            } else {
                NElement v = e.getValue();
                sb.append(v.isAnyStringOrName() ? v.asStringValue().get() : v.toString());
            }
        }
        return sb.append('}').toString();
    }
}
