package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationFields;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.naru.impl.util.StoredStringMap;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The registration store: four files on two axes.
 *
 * <p>The visibility axis is the one {@code env.tson} uses — public {@code registrations.tson}
 * (structure, params, {@code $NAME} references, checked in) and private
 * {@code local/config/registrations.tson} (literal secrets, gitignored). The scope axis is
 * {@link NaruRegistrationScope}: the project's own pair, and the user's machine-wide pair under
 * {@code ~/.naru}.
 *
 * <p>One registration may be <b>split</b> across all four: its endpoint in the user file, its
 * {@code apiKey} literal in the project private file, so a project can complete a personal
 * registration without copying it. Reads merge field by field, weakest layer first —
 * userPublic, userPrivate, projectPublic, projectPrivate — and writes recompute the split from
 * the registration being written and route it to the scope asked for. A {@code $NAME} reference
 * is not a secret, so it stays public even on an {@code apiKey}. An alias spelling
 * ({@code host}, {@code api_key}) folds onto its canonical field on the way in, so the merged
 * view and the per-field origins name the same thing.
 *
 * <p>A layer does not always get to overlay the one below it: when the two do not describe the
 * same endpoint, the stronger layer replaces the registration wholesale. If the project's {@code
 * fast} is a gemini registration and the user's {@code fast} is an OpenAI-compatible one, keeping
 * the user's fields as well would produce a registration whose key belongs to another API — the
 * kind of breakage that reads as "the model rejected my key" and costs an afternoon.
 */
public class NaruRegistrationStore {

    /** The conventional path of a scope's public file, below its base directory. */
    private static final String PUBLIC_PATH = ".naru/config/registrations.tson";
    /** The conventional path of a scope's private file, below its base directory. */
    private static final String PRIVATE_PATH = ".naru/local/config/registrations.tson";

    /** The two files of one scope, or null when the scope has no files at all. */
    private static final class Layer {
        final NaruRegistrationScope scope;
        final StoredStringMap<NElement> publicStore;
        final StoredStringMap<NElement> privateStore;

        Layer(NaruRegistrationScope scope, StoredStringMap<NElement> publicStore,
              StoredStringMap<NElement> privateStore) {
            this.scope = scope;
            this.publicStore = publicStore;
            this.privateStore = privateStore;
        }
    }

    /** A merged registration: its fields, and the scope each field was read from. */
    private static final class Merged {
        final Map<String, NElement> fields;
        final Map<String, NaruRegistrationScope> origins;

        Merged(Map<String, NElement> fields, Map<String, NaruRegistrationScope> origins) {
            this.fields = fields;
            this.origins = origins;
        }
    }

    /** The layers, weakest first: the last one to contribute a field owns it. */
    private final Layer[] layers;

    public NaruRegistrationStore(NPath publicPath, NPath privatePath) {
        this(null, null, publicPath, privatePath);
    }

    /**
     * The store for a project and its user home, using the conventional paths of both. A null
     * {@code userHome} gives a project-only store.
     */
    public static NaruRegistrationStore of(NPath userHome, NPath projectDirectory) {
        return new NaruRegistrationStore(
                userHome == null ? null : userHome.resolve(PUBLIC_PATH),
                userHome == null ? null : userHome.resolve(PRIVATE_PATH),
                projectDirectory.resolve(PUBLIC_PATH),
                projectDirectory.resolve(PRIVATE_PATH));
    }

    /**
     * The four files. The user pair is optional — a store built without a home directory, or given
     * only one of its two files, simply has no user layer — but the project pair is not: both of
     * its paths must be given, they are the store's own two files.
     */
    public NaruRegistrationStore(NPath userPublic, NPath userPrivate, NPath publicPath, NPath privatePath) {
        Layer user = (userPublic == null || userPrivate == null) ? null : new Layer(
                NaruRegistrationScope.USER, store(userPublic), store(userPrivate));
        Layer project = new Layer(NaruRegistrationScope.PROJECT, store(publicPath), store(privatePath));
        // weakest first: the last layer to contribute a field owns it
        this.layers = user == null ? new Layer[]{project} : new Layer[]{user, project};
    }

    private static StoredStringMap<NElement> store(NPath path) {
        return new StoredStringMap<>(path, NElement.class);
    }

    /**
     * The merged file view of one id, handed to the registration one field at a time: this map is the
     * store's own merge buffer, never the API a caller manipulates, so it is poured through
     * {@link NaruModelRegistration.Builder#put(String, NElement)} — which folds aliases and checks
     * kinds — rather than accepted as a loose parameter bag.
     */
    private static NaruModelRegistration build(String id, Map<String, NElement> fields) {
        NaruModelRegistration.Builder b = NaruModelRegistration.builder(id);
        fields.forEach(b::put);
        return b.build();
    }

    public NOptional<NaruModelRegistration> get(String id) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            return NOptional.ofEmpty();
        }
        Merged m = merge(iid);
        if (m == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(build(iid, m.fields));
    }

    /**
     * Every registration, by id, sorted — the merged view of all four files.
     *
     * <p>A hand-edited entry that writes a wire protocol id as its
     * {@code provider}, or a provider with no matching type, fails here with a
     * named error rather than silently registering nothing. An entry without a
     * {@code provider} is a generic endpoint (internal {@code custom} type).
     */
    public Map<String, NaruModelRegistration> toMap() {
        Map<String, NaruModelRegistration> out = new TreeMap<>();
        for (String k : allIds()) {
            out.put(k, build(k, merge(k).fields));
        }
        return out;
    }

    /**
     * Which layer each field of a registration came from, or empty when no file declares it.
     * What an editor needs before offering to delete: a project override is not the registration.
     */
    public NOptional<NaruRegistrationOrigin> origin(String id) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            return NOptional.ofEmpty();
        }
        Merged m = merge(iid);
        if (m == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(new NaruRegistrationOrigin(iid, m.origins));
    }

    /**
     * Creates or replaces a registration in the project scope, recomputing the visibility split.
     */
    public void put(NaruModelRegistration registration) {
        put(registration, NaruRegistrationScope.PROJECT);
    }

    /**
     * Creates or replaces a registration in one scope, recomputing the visibility split: literal
     * secrets go to that scope's private file, everything else (including {@code $NAME}
     * references) to its public one. Writing the project scope never touches the user files, so
     * a project cannot overwrite a personal key by saving a form.
     *
     * <p>The registration written names its own endpoint — {@code NaruModelRegistration} requires a
     * {@code provider} or a {@code protocol}, and neither is inherited. A scope that wants to
     * complete another's registration re-declares that identity and adds its own fields; that
     * repetition is what makes an override and a different-registration-with-the-same-id tellable.
     */
    public void put(NaruModelRegistration registration, NaruRegistrationScope scope) {
        Layer layer = layer(scope);
        NObjectElementBuilder pub = NElement.ofObjectBuilder();
        NObjectElementBuilder priv = NElement.ofObjectBuilder();
        boolean hasPrivate = false;
        for (NPairElement p : registration.storedPairs()) {
            String key = p.key().asStringValue().orNull();
            if (key == null) {
                continue;
            }
            if (NaruModelRegistration.isSecretLiteral(key, p.value())) {
                priv.set(key, p.value());
                hasPrivate = true;
            } else {
                pub.set(key, p.value());
            }
        }
        layer.publicStore.put(registration.id(), pub.build());
        if (hasPrivate) {
            layer.privateStore.put(registration.id(), priv.build());
        } else if (layer.privateStore.get(registration.id()).isPresent()) {
            // only a store that holds this id has anything to take away: deleting otherwise would
            // create an empty private file next to every registration that keeps no secret
            layer.privateStore.remove(registration.id());
        }
    }

    /**
     * Deletes a registration from the project scope's two files.
     *
     * @return true when it was present in at least one of them
     */
    public boolean remove(String id) {
        return remove(id, NaruRegistrationScope.PROJECT);
    }

    /**
     * Deletes a registration from one scope's two files. The other scope is left alone: removing
     * a project override that shadows a user registration uncovers the user registration, it does
     * not delete it — {@link #origin(String)} is how a caller learns that before promising it.
     */
    public boolean remove(String id, NaruRegistrationScope scope) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            return false;
        }
        Layer layer = layer(scope);
        boolean inPublic = layer.publicStore.get(iid).isPresent();
        boolean inPrivate = layer.privateStore.get(iid).isPresent();
        if (inPublic) {
            layer.publicStore.remove(iid);
        }
        if (inPrivate) {
            layer.privateStore.remove(iid);
        }
        return inPublic || inPrivate;
    }

    private Set<String> allIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Layer layer : layers) {
            ids.addAll(layer.publicStore.toMap().keySet());
            ids.addAll(layer.privateStore.toMap().keySet());
        }
        return ids;
    }

    private Layer layer(NaruRegistrationScope scope) {
        NaruRegistrationScope s = scope == null ? NaruRegistrationScope.PROJECT : scope;
        for (Layer layer : layers) {
            if (layer.scope == s) {
                return layer;
            }
        }
        throw new IllegalStateException("registration store has no " + s.word() + " layer");
    }

    /**
     * The merged fields of one id, or null when no layer declares it.
     *
     * <p>Layers apply weakest first; a layer whose identity — {@code provider} or {@code protocol} —
     * differs from what is already accumulated replaces the whole registration instead of
     * overlaying it, and takes the origins with it.
     */
    private Merged merge(String iid) {
        Map<String, NElement> fields = new LinkedHashMap<>();
        Map<String, NaruRegistrationScope> origins = new LinkedHashMap<>();
        boolean any = false;
        for (Layer layer : layers) {
            Map<String, NElement> declared = layerFields(layer, iid);
            if (declared == null) {
                continue;
            }
            any = true;
            if (!fields.isEmpty() && !sameEndpoint(fields, declared)) {
                fields.clear();
                origins.clear();
            }
            for (Map.Entry<String, NElement> e : declared.entrySet()) {
                fields.put(e.getKey(), e.getValue());
                origins.put(e.getKey(), layer.scope);
            }
        }
        return any ? new Merged(fields, origins) : null;
    }

    /** The scope's two files overlaid for one id, or null when neither declares it. */
    private static Map<String, NElement> layerFields(Layer layer, String iid) {
        NElement pub = layer.publicStore.get(iid).orNull();
        NElement priv = layer.privateStore.get(iid).orNull();
        if (pub == null && priv == null) {
            return null;
        }
        Map<String, NElement> m = new LinkedHashMap<>();
        Set<String> canonical = new LinkedHashSet<>();
        collectInto(m, canonical, pub);
        collectInto(m, canonical, priv);
        return m;
    }
    /**
     * Whether two layers describe the same endpoint: a layer that names no {@code provider} (or
     * no {@code protocol}) says nothing about it and cannot conflict. The comparison ignores case —
     * {@code provider: Gemini} and {@code provider: gemini} name one type, and the store keeps
     * provider values exactly as their author spelled them — while any other difference, however
     * close, is a different endpoint and replaces rather than overlays.
     */
    private static boolean sameEndpoint(Map<String, NElement> accumulated, Map<String, NElement> incoming) {
        for (String identity : NaruModelRegistration.IDENTITY_PARAMS) {
            String a = identityValue(accumulated.get(identity));
            String b = identityValue(incoming.get(identity));
            if (a != null && b != null && !a.equalsIgnoreCase(b)) {
                return false;
            }
        }
        return true;
    }

    private static String identityValue(NElement e) {
        if (e == null || e.isNull()) {
            return null;
        }
        return NStringUtils.stripToNull(e.asStringValue().orNull());
    }

    /**
     * One file's pairs, folded onto the vocabulary's canonical names as they are read. Folding here
     * rather than only at {@link NaruModelRegistration} construction is what keeps
     * {@link NaruRegistrationOrigin} honest: it and {@code NaruModelRegistrationRules} key their
     * per-field bookkeeping by the names {@link NaruModelRegistration#storedPairs()} reports, so a
     * {@code host} stored raw
     * would answer {@code scopeOf("url") == null} and be dropped from the file on the next scoped
     * save. A name the vocabulary does not declare keeps its own spelling — it belongs to whoever
     * declared it.
     *
     * <p>Within one scope, a canonical spelling beats an alias, whichever file came last:
     * {@code apiKey: $VAR} in the public pair is not silently displaced by {@code api_key: sk-…} in
     * the private one.
     */
    private static void collectInto(Map<String, NElement> m, Set<String> canonical, NElement element) {
        if (element == null || !element.isListContainer()) {
            return;
        }
        for (NPairElement p : element.asListContainer().get().namedPairs()) {
            String k = p.key().asStringValue().orNull();
            if (k == null) {
                continue;
            }
            NaruRegistrationFields.Field<?> f = NaruRegistrationFields.find(k);
            if (f == null) {
                m.put(k, p.value());
                continue;
            }
            String key = f.key();
            boolean canonicalSpelling = key.equalsIgnoreCase(k);
            if (!canonicalSpelling && canonical.contains(key)) {
                continue;
            }
            if (canonicalSpelling) {
                canonical.add(key);
            }
            m.put(key, p.value());
        }
    }

    /** The ids declared in one scope's two files, sorted — what a UI lists per file. */
    public Set<String> ids(NaruRegistrationScope scope) {
        Set<String> out = new TreeSet<>();
        for (Layer layer : layers) {
            if (layer.scope == scope) {
                out.addAll(layer.publicStore.toMap().keySet());
                out.addAll(layer.privateStore.toMap().keySet());
            }
        }
        return out;
    }
}
