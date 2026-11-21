package net.thevpc.naru.api.model;

import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NStringUtils;

import java.util.Map;

/**
 * A model call outside any workspace. Settings come from the given values, then system
 * properties; credentials from the given values, then the process environment.
 */
final class NaruStandaloneModelContext implements NaruModelContext {
    private final Map<String, String> values;

    NaruStandaloneModelContext(Map<String, String> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
    }

    @Override
    public NOptional<NElement> config(String key) {
        String v = given(key);
        if (v == null && key != null) {
            v = NStringUtils.stripToNull(System.getProperty(key));
        }
        return v == null ? NOptional.ofNamedEmpty(key) : NOptional.of(NElement.ofString(v));
    }

    @Override
    public String envValue(String name) {
        String v = given(name);
        return v != null ? v : (name == null ? null : NStringUtils.stripToNull(System.getenv(name)));
    }

    private String given(String key) {
        return key == null ? null : NStringUtils.stripToNull(values.get(key));
    }

    @Override
    public <T extends NaruSessionExtension> NOptional<T> sessionExtension(String name, Class<T> type) {
        return NOptional.ofNamedEmpty(name);
    }

    @Override
    public void reportRateLimits(NaruProviderRateLimitInfo info) {
    }

    @Override
    public void notice(NMsg message) {
    }

    @Override
    public NOptional<NPath> sessionDir() {
        return NOptional.ofNamedEmpty("sessionDir");
    }

    @Override
    public String sessionId() {
        return null;
    }

    @Override
    public long taskId() {
        return 0;
    }

    @Override
    public String taskName() {
        return null;
    }
}
