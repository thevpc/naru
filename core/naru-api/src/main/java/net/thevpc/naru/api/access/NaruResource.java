package net.thevpc.naru.api.access;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The concrete thing an access check is about: a typed value plus the locator
 * that a rule's patterns are matched against.
 *
 * <p>The locator is the load-bearing field. For files and directories it is an
 * absolute, {@code /}-separated path; for urls it is {@code scheme://host/path};
 * for tools, directives and models it is the bare name. Normalising here, at
 * construction, is what lets a rule store a single canonical pattern and have it
 * match however the call site phrased the resource.
 *
 * <p>{@link #scope()} keeps the friendly, project-relative rendering (what
 * {@code /permission list} shows a user) without polluting matching, which always
 * runs on the absolute locator. {@link #attributes()} carries whatever else a
 * domain wants a rule or a log line to see — tool tags, a url scheme, an HTTP
 * method — without widening this type for every one of them.
 */
public final class NaruResource implements NToElement {

    private final NaruResourceType type;
    private final String locator;
    private final String scope;
    private final Map<String, Object> attributes;

    private NaruResource(NaruResourceType type, String locator, String scope, Map<String, Object> attributes) {
        if (type == null) {
            throw new NIllegalArgumentException(NMsg.ofC("resource type must not be null"));
        }
        if (NBlankable.isBlank(locator)) {
            throw new NIllegalArgumentException(NMsg.ofC("resource locator must not be blank"));
        }
        this.type = type;
        this.locator = locator;
        this.scope = scope;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    // ── factories ────────────────────────────────────────────────────────────

    public static NaruResource of(NaruResourceType type, String locator) {
        return new NaruResource(type, locator, null, null);
    }

    public static NaruResource file(NPath path, String projectRel) {
        String loc = path == null ? null : path.toString();
        return new NaruResource(NaruResourceType.FILE, loc, projectRel, null);
    }

    public static NaruResource dir(NPath path, String projectRel) {
        String loc = path == null ? null : path.toString();
        return new NaruResource(NaruResourceType.DIR, loc, projectRel, null);
    }

    public static NaruResource tool(String toolName, Set<String> toolTags) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (toolTags != null && !toolTags.isEmpty()) {
            attrs.put("tags", toolTags);
        }
        return new NaruResource(NaruResourceType.TOOL, toolName, null, attrs);
    }

    public static NaruResource directive(String directiveName) {
        return new NaruResource(NaruResourceType.DIRECTIVE, directiveName, null, null);
    }

    public static NaruResource url(String url) {
        return new NaruResource(NaruResourceType.URL, url, null, null);
    }

    public static NaruResource model(String provider, String model) {
        String loc = provider == null ? model : provider + "/" + model;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("provider", provider);
        attrs.put("model", model);
        return new NaruResource(NaruResourceType.MODEL, loc, null, attrs);
    }

    public static NaruResource shell(String command) {
        return new NaruResource(NaruResourceType.SHELL, command, null, null);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public NaruResourceType type() {
        return type;
    }

    public String locator() {
        return locator;
    }

    public NOptional<String> scope() {
        return NOptional.ofNullable(scope);
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public NOptional<Object> attribute(String name) {
        return NOptional.ofNullable(attributes.get(name));
    }

    public NaruResource withAttribute(String name, Object value) {
        Map<String, Object> a = new LinkedHashMap<>(attributes);
        if (value == null) {
            a.remove(name);
        } else {
            a.put(name, value);
        }
        return new NaruResource(type, locator, scope, a);
    }

    public NaruResource withScope(String scope) {
        return new NaruResource(type, locator, scope, attributes);
    }

    // ── persistence ──────────────────────────────────────────────────────────

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("type", type.id());
        o.set("locator", locator);
        if (scope != null) {
            o.set("scope", scope);
        }
        if (!attributes.isEmpty()) {
            NObjectElementBuilder a = NObjectElementBuilder.of();
            for (Map.Entry<String, Object> e : attributes.entrySet()) {
                Object v = e.getValue();
                a.set(e.getKey(), v == null ? null : NElement.ofString(String.valueOf(v)));
            }
            o.set("attributes", a.build());
        }
        return o.build();
    }

    public static NaruResource of(NElement element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (!element.isAnyObject()) {
            throw new NIllegalArgumentException(NMsg.ofC("resource must be an object, got %s", element));
        }
        NObjectElement o = element.asObject().get();
        NaruResourceType type = NaruResourceType.of(o.getStringValue("type").orNull());
        String locator = o.getStringValue("locator").orNull();
        String scope = o.getStringValue("scope").orNull();
        Map<String, Object> attrs = new LinkedHashMap<>();
        NElement ae = o.get("attributes").orNull();
        if (ae != null && ae.isAnyObject()) {
            for (NElement c : ae.asObject().get().children()) {
                if (c.isNamedPair()) {
                    String k = c.asPair().get().key().asStringValue().orNull();
                    String v = c.asPair().get().value().asStringValue().orNull();
                    if (k != null) {
                        attrs.put(k, v);
                    }
                }
            }
        }
        return new NaruResource(type, locator, scope, attrs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NaruResource)) {
            return false;
        }
        NaruResource r = (NaruResource) o;
        return type.equals(r.type) && locator.equals(r.locator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, locator);
    }

    @Override
    public String toString() {
        return type.id() + " " + locator;
    }
}
