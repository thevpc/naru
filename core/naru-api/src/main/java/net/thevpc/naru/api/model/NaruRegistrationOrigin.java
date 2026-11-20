package net.thevpc.naru.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where the fields of a merged registration actually came from.
 *
 * <p>{@link net.thevpc.naru.api.agent.NaruSession#registrations()} answers "what is the registration", which is enough to
 * use it but not enough to <b>edit</b> it: the same id can be declared in the user layer and
 * overridden field by field in the project, and "delete this registration" means something
 * different in each case. This record is what lets an editor say {@code apiKey comes from your
 * user config; deleting the project entry will not remove it}.
 *
 * <p>Field names are the registration parameter names ({@code provider}, {@code url},
 * {@code apiKey}, ...). A field that the merged registration does not carry is absent here.
 */
public record NaruRegistrationOrigin(String id, Map<String, NaruRegistrationScope> fieldScopes) {

    public NaruRegistrationOrigin {
        fieldScopes = Collections.unmodifiableMap(new LinkedHashMap<>(
                fieldScopes == null ? Map.of() : fieldScopes));
    }

    /** Every field attributed to one scope — a registration that exists in only one layer. */
    public static NaruRegistrationOrigin of(String id, NaruRegistrationScope scope, Set<String> fields) {
        Map<String, NaruRegistrationScope> m = new LinkedHashMap<>();
        for (String f : fields) {
            m.put(f, scope);
        }
        return new NaruRegistrationOrigin(id, m);
    }

    /**
     * The layer that owns this registration as a whole: {@code PROJECT} when the project declares
     * it, {@code USER} when only the user layer does. This is what a write or a delete acts on.
     */
    public NaruRegistrationScope scope() {
        return fieldScopes.containsValue(NaruRegistrationScope.PROJECT)
                ? NaruRegistrationScope.PROJECT : NaruRegistrationScope.USER;
    }

    /** The layer a single field was read from, or null when the registration has no such field. */
    public NaruRegistrationScope scopeOf(String field) {
        return fieldScopes.get(field);
    }

    /** True when the layer declares this id at all — even if every one of its fields is overridden. */
    public boolean isDeclaredIn(NaruRegistrationScope scope) {
        return fieldScopes.containsValue(scope);
    }

    /** The fields a layer contributes to the merged registration. */
    public Set<String> fieldsOf(NaruRegistrationScope scope) {
        Set<String> out = new LinkedHashSet<>();
        fieldScopes.forEach((k, v) -> {
            if (v == scope) {
                out.add(k);
            }
        });
        return out;
    }
}
