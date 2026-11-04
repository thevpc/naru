package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NLiteral;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The registration parameter vocabulary: every parameter a {@link NaruModelRegistration} is made
 * of, described exactly once.
 *
 * <p>Before this table existed, the same ~24 names were enumerated independently in six places —
 * the secret-name set, the api-key probe, the display mask, the local-endpoint url aliases, the
 * {@code /model} flag completion and the desktop's suggestion guesses — and the copies had already
 * drifted apart: a stored {@code api_key} was filed as a secret and then never read back, while a
 * stored {@code key} was treated as secret by the store and printed whole by the provider. One
 * table with a {@code secret} and an {@code identity} flag is what makes that disagreement
 * unrepresentable rather than a thing to remember.
 *
 * <p>Declaration order is meaningful twice over: it is the order {@code toElement()} writes the
 * file in (so a hand-edited file keeps reading top-down: identity, endpoint, pins, sampling,
 * flags, timeouts) and it is the field order a form renders. Every field also names its
 * {@link Group}, so the form can put a heading over each of those blocks without keeping a second
 * list of names to place it by.
 *
 * <p>Aliases are folded <b>here</b> and nowhere else. A file that spells its endpoint {@code host}
 * or its key {@code api_key} parses to the canonical field, and the next write stores the canonical
 * name — the six-name probe that used to run on every read is gone.
 *
 * <p>Parameters this table does not declare are not rejected: they live in
 * {@link NaruModelRegistration#extras()} and reach the provider untouched, which is how a provider
 * extension ships a knob core has never heard of without a core release.
 *
 * @see <a href="../../../../../../documentation/plans/model-registration-typed-fields.md">plan</a>
 */
public final class NaruRegistrationFields {

    /**
     * The shape a stored value takes: decides parse, render, and the widget a form uses.
     *
     * <p>{@link #RAW} is for a parameter whose shape is not core's to decide. {@code thinkingTags}
     * is the case that forced it: it is written as an {@code {openTag, closeTag}} object by hand and
     * as an {@code open,close} list by {@code --thinkingTags}, and both spellings mean the same tag
     * pair. Coercing either into a list would silently turn a hand-edited object into
     * {@code no tags → native-only reasoning}, so a raw parameter is stored and returned untouched
     * and the consumer that understands both shapes keeps doing so.
     */
    public enum Kind {
        TEXT, INT, FLOAT, BOOL, TEXT_LIST, RAW
    }

    /**
     * The section a parameter belongs to — the banner its declaration already sits under, said out
     * loud so a form can group twenty-four rows without re-enumerating the names to decide where a
     * heading goes. Declaration order keeps every group contiguous; a field that joins a section out
     * of order would simply render in its place, not in a second one.
     */
    public enum Group {
        IDENTITY("Identity"),
        ENDPOINT("Endpoint"),
        PINS("Models"),
        SAMPLING("Sampling"),
        BEHAVIOUR("Behaviour"),
        TIMEOUTS("Timeouts");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        /** What a heading above a block of these fields should read. */
        public String label() {
            return label;
        }
    }

    /**
     * One registration parameter. The typed getter reads the registration's field, the setter writes
     * the builder's, and {@code parse}/{@code render} cross the storage boundary by {@link Kind} —
     * leniently, because these files are hand-edited and a {@code temperature: 0.7} written as a
     * string is still a float.
     */
    public static final class Field<T> {
        private final String key;
        private final List<String> aliases;
        private final Group group;
        private final Kind kind;
        private final boolean secret;
        private final boolean identity;
        private final Function<NaruModelRegistration, T> get;
        private final BiConsumer<NaruModelRegistration.Builder, T> set;

        Field(String key, Group group, Kind kind, boolean secret, boolean identity, List<String> aliases,
              Function<NaruModelRegistration, T> get, BiConsumer<NaruModelRegistration.Builder, T> set) {
            this.key = key;
            this.group = group;
            this.kind = kind;
            this.secret = secret;
            this.identity = identity;
            this.aliases = aliases;
            this.get = get;
            this.set = set;
        }

        /** The canonical name written to the file. */
        public String key() {
            return key;
        }

        /** Other spellings accepted when reading; never written. */
        public List<String> aliases() {
            return aliases;
        }

        /** The section a form groups this field under. */
        public Group group() {
            return group;
        }

        public Kind kind() {
            return kind;
        }

        /** A literal value goes to the private file and masks wherever it prints. */
        public boolean isSecret() {
            return secret;
        }

        /** A layer that rewrites this replaces the registration instead of overlaying it. */
        public boolean isIdentity() {
            return identity;
        }

        /** The typed value, or null when unset. Unset is not false and not zero. */
        public T get(NaruModelRegistration registration) {
            return get.apply(registration);
        }

        public NOptional<T> find(NaruModelRegistration registration) {
            return NOptional.of(get.apply(registration));
        }

        /** Write the value into a builder; a null clears the field. */
        public void set(NaruModelRegistration.Builder builder, T value) {
            set.accept(builder, value);
        }

        /** Read the stored form, folding a string spelling of the value when the kind allows it. */
        public T parse(NElement stored) {
            return parseValue(stored, kind);
        }

        /** The element to write. Null values are never rendered — absence is a removed key. */
        public NElement render(T value) {
            return renderValue(value, kind);
        }

        @Override
        public String toString() {
            return key;
        }
    }

    // ── identity ──────────────────────────────────────────────────────────────────

    public static final Field<String> PROVIDER = field("provider", Group.IDENTITY, Kind.TEXT, false, true,
            List.of(), NaruModelRegistration::rawProvider, NaruModelRegistration.Builder::provider);
    public static final Field<String> PROTOCOL = field("protocol", Group.IDENTITY, Kind.TEXT, false, true,
            List.of(), NaruModelRegistration::rawProtocol, NaruModelRegistration.Builder::protocol);

    // ── endpoint ──────────────────────────────────────────────────────────────────

    public static final Field<String> URL = field("url", Group.ENDPOINT, Kind.TEXT, false, false,
            List.of("baseUrl", "baseURL", "endpoint", "host"),
            NaruModelRegistration::rawUrl, NaruModelRegistration.Builder::url);
    public static final Field<String> API_KEY = field("apiKey", Group.ENDPOINT, Kind.TEXT, true, false,
            List.of("apikey", "api_key", "key"),
            NaruModelRegistration::rawApiKey, NaruModelRegistration.Builder::apiKey);
    public static final Field<String> CHAT_PATH = field("chatPath", Group.ENDPOINT, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawChatPath, NaruModelRegistration.Builder::chatPath);

    // ── model pins ────────────────────────────────────────────────────────────────

    public static final Field<String> MODEL = field("model", Group.PINS, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawModel, NaruModelRegistration.Builder::model);
    public static final Field<List<String>> MODELS = field("models", Group.PINS, Kind.TEXT_LIST, false, false,
            List.of(), NaruModelRegistration::rawModels, NaruModelRegistration.Builder::models);

    // ── sampling ──────────────────────────────────────────────────────────────────

    public static final Field<Float> TEMPERATURE = field("temperature", Group.SAMPLING, Kind.FLOAT, false, false,
            List.of(), NaruModelRegistration::rawTemperature, NaruModelRegistration.Builder::temperature);
    public static final Field<Integer> CONTEXT_LENGTH = field("contextLength", Group.SAMPLING, Kind.INT, false, false,
            List.of(), NaruModelRegistration::rawContextLength, NaruModelRegistration.Builder::contextLength);
    public static final Field<Integer> MAX_TOKENS = field("maxTokens", Group.SAMPLING, Kind.INT, false, false,
            List.of(), NaruModelRegistration::rawMaxTokens, NaruModelRegistration.Builder::maxTokens);
    public static final Field<Float> NUCLEUS_THRESHOLD = field("nucleusThreshold", Group.SAMPLING, Kind.FLOAT, false, false,
            List.of(), NaruModelRegistration::rawNucleusThreshold, NaruModelRegistration.Builder::nucleusThreshold);
    public static final Field<Integer> CANDIDATE_COUNT = field("candidateCount", Group.SAMPLING, Kind.INT, false, false,
            List.of(), NaruModelRegistration::rawCandidateCount, NaruModelRegistration.Builder::candidateCount);
    public static final Field<List<String>> STOP = field("stop", Group.SAMPLING, Kind.TEXT_LIST, false, false,
            List.of(), NaruModelRegistration::rawStop, NaruModelRegistration.Builder::stop);

    // ── behaviour ─────────────────────────────────────────────────────────────────

    public static final Field<NElement> THINKING_TAGS = field("thinkingTags", Group.BEHAVIOUR, Kind.RAW,
            false, false, List.of(), NaruModelRegistration::rawThinkingTags,
            NaruModelRegistration.Builder::thinkingTags);
    public static final Field<Boolean> TOOLS = field("tools", Group.BEHAVIOUR, Kind.BOOL, false, false,
            List.of(), NaruModelRegistration::rawTools, NaruModelRegistration.Builder::tools);
    public static final Field<Boolean> PROBE = field("probe", Group.BEHAVIOUR, Kind.BOOL, false, false,
            List.of(), NaruModelRegistration::rawProbe, NaruModelRegistration.Builder::probe);
    public static final Field<Boolean> ENABLED = field("enabled", Group.BEHAVIOUR, Kind.BOOL, false, false,
            List.of(), NaruModelRegistration::rawEnabled, NaruModelRegistration.Builder::enabled);
    public static final Field<Boolean> FREE = field("free", Group.BEHAVIOUR, Kind.BOOL, false, false,
            List.of(), NaruModelRegistration::rawFree, NaruModelRegistration.Builder::free);
    public static final Field<Boolean> LOCAL = field("local", Group.BEHAVIOUR, Kind.BOOL, false, false,
            List.of(), NaruModelRegistration::rawLocal, NaruModelRegistration.Builder::local);

    // ── timeouts (duration words as stored; parsing belongs to NaruModelTimeouts) ──

    public static final Field<String> TIMEOUT = field("timeout", Group.TIMEOUTS, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawTimeout, NaruModelRegistration.Builder::timeout);
    public static final Field<String> READ_TIMEOUT = field("readTimeout", Group.TIMEOUTS, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawReadTimeout, NaruModelRegistration.Builder::readTimeout);
    public static final Field<String> CONNECT_TIMEOUT = field("connectTimeout", Group.TIMEOUTS, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawConnectTimeout, NaruModelRegistration.Builder::connectTimeout);
    public static final Field<String> RETRY_PERIOD = field("retryPeriod", Group.TIMEOUTS, Kind.TEXT, false, false,
            List.of(), NaruModelRegistration::rawRetryPeriod, NaruModelRegistration.Builder::retryPeriod);
    public static final Field<Integer> MAX_RETRIES = field("maxRetries", Group.TIMEOUTS, Kind.INT, false, false,
            List.of(), NaruModelRegistration::rawMaxRetries, NaruModelRegistration.Builder::maxRetries);

    /** Declaration order is file order and form order. */
    private static final List<Field<?>> FIELDS = List.of(
            PROVIDER, PROTOCOL,
            URL, API_KEY, CHAT_PATH,
            MODEL, MODELS,
            TEMPERATURE, CONTEXT_LENGTH, MAX_TOKENS, NUCLEUS_THRESHOLD, CANDIDATE_COUNT, STOP,
            THINKING_TAGS, TOOLS, PROBE, ENABLED, FREE, LOCAL,
            TIMEOUT, READ_TIMEOUT, CONNECT_TIMEOUT, RETRY_PERIOD, MAX_RETRIES);

    private static final List<String> BOOLEAN_VALUES = List.of("true", "false");

    private NaruRegistrationFields() {
    }

    public static List<Field<?>> fields() {
        return FIELDS;
    }

    /**
     * The field named by a canonical key, an alias or any casing — the only name matching that
     * happens anywhere. The returned field is matched against the canonical names of scalar kinds,
     * so a caller that only accepts a declared parameter uses this as its validator.
     */
    public static Field<?> find(String anyName) {
        if (anyName == null) {
            return null;
        }
        String n = NStringUtils.strip(anyName);
        if (n.isEmpty()) {
            return null;
        }
        for (Field<?> f : FIELDS) {
            if (f.key.equalsIgnoreCase(n)) {
                return f;
            }
        }
        for (Field<?> f : FIELDS) {
            for (String a : f.aliases) {
                if (a.equalsIgnoreCase(n)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** The canonical names, in file order. What {@code /model} completes and a form iterates. */
    public static List<String> keys() {
        List<String> out = new ArrayList<>(FIELDS.size());
        for (Field<?> f : FIELDS) {
            out.add(f.key);
        }
        return out;
    }

    /** The values a parameter can sensibly take, when it has a closed set. */
    public static List<String> suggestedValues(Field<?> field) {
        if (field == null) {
            return List.of();
        }
        if (field == PROTOCOL || field == PROVIDER) {
            // an id the session's registry owns, not a fixed vocabulary: the host supplies it
            return List.of();
        }
        return field.kind == Kind.BOOL ? BOOLEAN_VALUES : List.of();
    }

    private static <T> Field<T> field(String key, Group group, Kind kind,
                                      boolean secret, boolean identity,
                                      List<String> aliases,
                                      Function<NaruModelRegistration, T> get,
                                      BiConsumer<NaruModelRegistration.Builder, T> set) {
        return new Field<>(key, group, kind, secret, identity, aliases, get, set);
    }

    /**
     * A stored element as the declared kind wants it. Lenient by necessity: the command line stores a
     * parsed number where it can and a word where it cannot, and readers hand-edit these files, so
     * {@code "0.7"} is a float and {@code "true"} is a boolean rather than a rejected write.
     */
    @SuppressWarnings("unchecked")
    private static <T> T parseValue(NElement e, Kind kind) {
        if (e == null || e.isNull()) {
            return null;
        }
        switch (kind) {
            case RAW:
                return (T) e;
            case TEXT: {
                String s = e.asStringValue().orNull();
                return (T) (NBlankable.isBlank(s) ? null : s);
            }
            case INT: {
                // an integer parameter is stored as a long element (that is what the command line
                // writes), so read through both spellings before falling back to a hand-typed word
                NOptional<Integer> i = e.asIntValue();
                if (i.isPresent()) {
                    return (T) (Integer) i.get();
                }
                NOptional<Long> l = e.asLongValue();
                if (l.isPresent()) {
                    return (T) (Integer) Math.toIntExact(l.get());
                }
                String s = textOf(e);
                if (s == null) {
                    return null;
                }
                NOptional<Long> pl = NLiteral.of(s).asLong();
                return pl.isPresent() ? (T) (Integer) Math.toIntExact(pl.get()) : null;
            }
            case FLOAT: {
                NOptional<Float> f = e.asFloatValue();
                if (f.isPresent()) {
                    return (T) (Float) f.get();
                }
                String s = textOf(e);
                if (s == null) {
                    return null;
                }
                NOptional<Float> p = NLiteral.of(s).asFloat();
                return p.isPresent() ? (T) (Float) p.get() : null;
            }
            case BOOL: {
                NOptional<Boolean> b = e.asBooleanValue();
                if (b.isPresent()) {
                    return (T) (Boolean) b.get();
                }
                String s = textOf(e);
                if (s == null) {
                    return null;
                }
                NOptional<Boolean> p = NLiteral.of(s.trim().toLowerCase(Locale.ROOT)).asBoolean();
                return p.isPresent() ? (T) (Boolean) p.get() : null;
            }
            case TEXT_LIST:
            default:
                return (T) parseList(e);
        }
    }

    /**
     * A list parameter as it may be written: an array, or one comma-separated string. Public because
     * a {@code thinkingTags} value reaches the session in either spelling and both must mean the
     * same tag pair.
     */
    public static List<String> listValueOf(NElement e) {
        if (e == null || e.isNull()) {
            return List.of();
        }
        return parseList(e);
    }

    /** A list as stored, or a comma-separated word. Mirrors what {@code stringList} always did. */
    private static List<String> parseList(NElement e) {
        if (e.isArray()) {
            List<String> out = new ArrayList<>();
            for (NElement c : e.asArray().map(x -> x.children()).orElse(List.of())) {
                String s = c.asStringValue().orNull();
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
            return Collections.unmodifiableList(out);
        }
        String s = e.asStringValue().orNull();
        if (NBlankable.isBlank(s)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String x = part.trim();
            if (!x.isEmpty()) {
                out.add(x);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static NElement renderValue(Object value, Kind kind) {
        if (value == null) {
            return null;
        }
        switch (kind) {
            case RAW:
                return (NElement) value;
            case TEXT:
                return NElement.ofString(String.valueOf(value));
            case INT:
                return NElement.of(((Integer) value).longValue());
            case FLOAT:
                return NElement.of((Float) value);
            case BOOL:
                return NElement.of((Boolean) value);
            case TEXT_LIST:
            default: {
                List<?> items = (List<?>) value;
                NElement[] arr = new NElement[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    arr[i] = NElement.ofString(String.valueOf(items.get(i)));
                }
                return NElement.ofArray(arr);
            }
        }
    }

    private static String textOf(NElement e) {
        if (!e.isAnyStringOrName()) {
            return null;
        }
        String s = e.asStringValue().orNull();
        return NBlankable.isBlank(s) ? null : s.trim();
    }
}
