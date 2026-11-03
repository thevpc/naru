package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.desktop.NaruDesktop;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NStringUtils;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * The view axis of the model picker: what this window chooses to show, per project.
 *
 * <p>It is deliberately not a {@code model.filter.*} config key. Those are the <b>policy</b> axis —
 * checked into {@code .naru/config/env.tson}, inherited by every session of the project, and
 * enforced by the engine, which refuses a model a policy excludes. This class is the other half: a
 * reader hiding a provider they never use must not change what their teammates are allowed to run,
 * so it lives in {@link Preferences} on this machine and never touches a project file.
 *
 * <p>Two things are remembered. The <b>hidden</b> lists (a provider, or one exact
 * {@code provider/model}) keep a listing readable when a registration offers forty models; and the
 * <b>filter state</b> — the typed text, the sort, the facet checkboxes — is restored, so a session
 * opened an hour later reopens the chooser where it was left. Both are keyed by the project's
 * absolute path: the provider worth hiding while doing offline work is the one wanted while
 * debugging a remote endpoint, and the two projects should not have to trade settings.
 */
public final class ModelViewPreferences {

    private static final String HIDDEN_PROVIDERS = "hiddenProviders";
    private static final String HIDDEN_MODELS = "hiddenModels";
    private static final String FILTERS = "filters";

    private final Preferences node;
    /** The prefs entry name for this project, or null when there is no project to remember for. */
    private final String key;
    private NObjectElement stored;

    private ModelViewPreferences(Preferences node, String key, NObjectElement stored) {
        this.node = node;
        this.key = key;
        this.stored = stored;
    }

    /**
     * The view settings of one project, or an inert instance when no project is open: a picker with
     * no project of its own has nowhere to write, and must not borrow another window's slot.
     */
    public static ModelViewPreferences of(NPath projectDirectory) {
        Preferences node = Preferences.userNodeForPackage(NaruDesktop.class).node("model-view");
        String key = keyOf(projectDirectory);
        if (key == null) {
            return new ModelViewPreferences(node, null, NElement.ofObjectBuilder().build());
        }
        return new ModelViewPreferences(node, key, read(node.get(key, null)));
    }

    /** Whether anything here can be remembered; false when no project is open. */
    public boolean isPersistent() {
        return key != null;
    }

    public Set<String> hiddenProviders() {
        return list(HIDDEN_PROVIDERS);
    }

    public Set<String> hiddenModels() {
        return list(HIDDEN_MODELS);
    }

    /**
     * Whether the picker should drop this model: its provider hidden as a whole, or this exact
     * {@code provider/model}. Compared case-insensitively — a provider id comes off a registration
     * its author spelled, and {@code Ollama} and {@code ollama} name one provider.
     */
    public boolean isHidden(String provider, String model) {
        if (provider == null) {
            return false;
        }
        if (containsIgnoreCase(hiddenProviders(), provider)) {
            return true;
        }
        return model != null && containsIgnoreCase(hiddenModels(), provider + "/" + model);
    }

    public void hideProvider(String provider, boolean hidden) {
        if (provider == null) {
            return;
        }
        Set<String> current = hiddenProviders();
        String name = spelledAs(current, provider);
        if (hidden ? current.add(name) : current.remove(name)) {
            write(HIDDEN_PROVIDERS, new ArrayList<>(current));
        }
    }

    public void hideModel(String provider, String model, boolean hidden) {
        if (provider == null || model == null) {
            return;
        }
        String key = provider + "/" + model;
        Set<String> current = hiddenModels();
        String name = spelledAs(current, key);
        if (hidden ? current.add(name) : current.remove(name)) {
            write(HIDDEN_MODELS, new ArrayList<>(current));
        }
    }

    /**
     * What the picker should list: {@code infos} with the hidden ones dropped, in the order given.
     * The rule lives beside the lists rather than inline in the chooser so the listing a reader sees
     * is the listing the tests pin.
     */
    public List<NaruModelInfo> visible(List<NaruModelInfo> infos) {
        List<NaruModelInfo> out = new ArrayList<>();
        for (NaruModelInfo info : infos) {
            if (!isHidden(info.provider(), info.model())) {
                out.add(info);
            }
        }
        return out;
    }

    /** One remembered facet of the chooser, spelled the way the widget typed it. */
    public String filter(String name) {
        NElement filters = stored.get(FILTERS).orNull();
        NObjectElement o = filters == null ? null : filters.asObject().orNull();
        if (o == null) {
            return null;
        }
        NElement v = o.get(name).orNull();
        return v == null ? null : v.asStringValue().orNull();
    }

    /**
     * Remember one facet. A blank value forgets it instead: a filter restored to the empty string is
     * indistinguishable from one never set, and storing it would keep the blob full of noise.
     */
    public void setFilter(String name, String value) {
        if (name == null) {
            return;
        }
        NObjectElementBuilder filters = builderOf(stored.get(FILTERS).orNull());
        if (NStringUtils.isBlank(value)) {
            filters.removePair(name);
        } else {
            filters.set(name, NElement.of(value));
        }
        NObjectElementBuilder root = stored.builder();
        if (filters.size() == 0) {
            root.removePair(FILTERS);
        } else {
            root.set(FILTERS, filters.build());
        }
        commit(root.build());
    }

    /** Forget every view setting of this project. */
    public void clear() {
        if (key != null) {
            node.remove(key);
        }
        stored = NElement.ofObjectBuilder().build();
    }

    // ── storage ───────────────────────────────────────────────────────────────

    /**
     * The prefs entry name of a project. A path is not a legal entry name (it is full of slashes,
     * which are node separators), and the alternative — nesting a node per directory segment —
     * would key the settings by whatever the reader named their checkout rather than by the
     * checkout itself. Normalized first, so a project opened through a symlink or a trailing slash
     * keeps the settings it already had.
     */
    private static String keyOf(NPath projectDirectory) {
        if (projectDirectory == null) {
            return null;
        }
        String path = projectDirectory.toAbsolute().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static NObjectElement read(String text) {
        if (NStringUtils.isBlank(text)) {
            return NElement.ofObjectBuilder().build();
        }
        try {
            NObjectElement o = NElementReader.ofTson().read(text).asObject().orNull();
            return o == null ? NElement.ofObjectBuilder().build() : o;
        } catch (RuntimeException e) {
            // a blob this build cannot read is worth less than a picker that still opens: start over,
            // and the next write replaces it
            return NElement.ofObjectBuilder().build();
        }
    }

    private static NObjectElementBuilder builderOf(NElement e) {
        NObjectElement o = e == null ? null : e.asObject().orNull();
        return o == null ? NElement.ofObjectBuilder() : o.builder();
    }

    private void write(String field, List<String> values) {
        if (key == null) {
            return;
        }
        NObjectElementBuilder root = stored.builder();
        if (values.isEmpty()) {
            root.removePair(field);
        } else {
            root.set(field, NElement.of(values));
        }
        commit(root.build());
    }

    private void commit(NElement root) {
        if (key == null) {
            return;
        }
        StringWriter sw = new StringWriter();
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.COMPACT).write(root, sw);
        node.put(key, sw.toString());
        // the written tree is now the stored one; leaving the instance on the copy it read at
        // construction makes a hide followed by an isHidden report the state before the hide
        stored = root.asObject().get();
    }

    private Set<String> list(String field) {
        Set<String> out = new LinkedHashSet<>();
        NElement e = stored.get(field).orNull();
        if (e != null && e.isListContainer()) {
            for (NElement c : e.asListContainer().get().children()) {
                String s = c.asStringValue().orNull();
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(Set<String> values, String wanted) {
        return wanted != null && values.stream().anyMatch(v -> v.equalsIgnoreCase(wanted));
    }

    /** The spelling already in the set, so hiding then un-hiding one name cannot fork it into two. */
    private static String spelledAs(Set<String> current, String wanted) {
        for (String v : current) {
            if (v.equalsIgnoreCase(wanted)) {
                return v;
            }
        }
        return wanted;
    }
}
