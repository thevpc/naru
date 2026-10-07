package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelRegistration;
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

/**
 * The registration store: two files on the same visibility axis as
 * {@code env.tson} — public {@code .naru/config/registrations.tson} (structure,
 * params, {@code $NAME} references, checked in) and private
 * {@code .naru/local/config/registrations.tson} (literal secrets, gitignored).
 *
 * <p>One registration may be <b>split</b> across the two: its {@code apiKey}
 * literal lives private, its {@code provider} and parameters public. Reads always
 * merge the two field by field (private overlaying public); writes recompute the
 * split from the registration being written. A {@code $NAME} reference is not a
 * secret, so it stays public even on an {@code apiKey}.
 */
public class NaruRegistrationStore {

    private final StoredStringMap<NElement> publicStore;
    private final StoredStringMap<NElement> privateStore;

    public NaruRegistrationStore(NPath publicPath, NPath privatePath) {
        publicStore = new StoredStringMap<>(publicPath, NElement.class);
        privateStore = new StoredStringMap<>(privatePath, NElement.class);
    }

    public NOptional<NaruModelRegistration> get(String id) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            return NOptional.ofEmpty();
        }
        NElement p = publicStore.get(iid).orNull();
        NElement q = privateStore.get(iid).orNull();
        if (p == null && q == null) {
            return NOptional.ofEmpty();
        }
        return NOptional.of(NaruModelRegistration.of(iid, merge(p, q)));
    }

    /**
     * Every registration, by id, sorted — the merged public+private view.
     *
     * <p>A hand-edited entry that writes a wire protocol id as its
     * {@code provider}, or a provider with no matching type, fails here with a
     * named error rather than silently registering nothing. An entry without a
     * {@code provider} is a generic endpoint (internal {@code custom} type).
     */
    public Map<String, NaruModelRegistration> toMap() {
        Map<String, NElement> pub = publicStore.toMap();
        Map<String, NElement> priv = privateStore.toMap();
        Map<String, NaruModelRegistration> out = new TreeMap<>();
        Set<String> ids = new LinkedHashSet<>(pub.keySet());
        ids.addAll(priv.keySet());
        for (String k : ids) {
            out.put(k, NaruModelRegistration.of(k, merge(pub.get(k), priv.get(k))));
        }
        return out;
    }

    /**
     * Creates or replaces a registration, recomputing the visibility split:
     * literal secrets go to the private file, everything else (including
     * {@code $NAME} references) to the public one.
     */
    public void put(NaruModelRegistration registration) {
        NObjectElementBuilder pub = NElement.ofObjectBuilder();
        NObjectElementBuilder priv = NElement.ofObjectBuilder();
        boolean hasPrivate = false;
        for (Map.Entry<String, NElement> e : registration.params().entrySet()) {
            if (NaruModelRegistration.isSecretLiteral(e.getKey(), e.getValue())) {
                priv.set(e.getKey(), e.getValue());
                hasPrivate = true;
            } else {
                pub.set(e.getKey(), e.getValue());
            }
        }
        publicStore.put(registration.id(), pub.build());
        if (hasPrivate) {
            privateStore.put(registration.id(), priv.build());
        } else {
            privateStore.remove(registration.id());
        }
    }

    /**
     * Deletes a registration from both files.
     *
     * @return true when it was present in at least one of them
     */
    public boolean remove(String id) {
        String iid = id == null ? null : NStringUtils.stripToNull(id);
        if (iid == null) {
            return false;
        }
        boolean inPublic = publicStore.get(iid).isPresent();
        boolean inPrivate = privateStore.get(iid).isPresent();
        if (inPublic) {
            publicStore.remove(iid);
        }
        if (inPrivate) {
            privateStore.remove(iid);
        }
        return inPublic || inPrivate;
    }

    /**
     * Private fields overlay the public ones, field by field: one registration is
     * split across the files, not stored twice in two versions.
     */
    private static Map<String, NElement> merge(NElement pub, NElement priv) {
        Map<String, NElement> m = new LinkedHashMap<>();
        collectInto(m, pub);
        collectInto(m, priv);
        return m;
    }

    private static void collectInto(Map<String, NElement> m, NElement element) {
        if (element != null && element.isListContainer()) {
            for (NPairElement p : element.asListContainer().get().namedPairs()) {
                String k = p.key().asStringValue().orNull();
                if (k != null) {
                    m.put(k, p.value());
                }
            }
        }
    }
}
