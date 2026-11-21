package net.thevpc.naru.api.model;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

/**
 * A model call made on behalf of a session, with no task: listing a provider's models,
 * building a protocol for the registry, titling a session.
 */
class NaruSessionModelContext implements NaruModelContext {
    protected final NaruSession session;

    NaruSessionModelContext(NaruSession session) {
        this.session = session;
    }

    @Override
    public NOptional<NElement> config(String key) {
        return NaruTaskConfig.find(session, key);
    }

    @Override
    public String envValue(String name) {
        return NaruModelRegistration.envValue(session, name);
    }

    @Override
    public <T extends NaruSessionExtension> NOptional<T> sessionExtension(String name, Class<T> type) {
        if (session.registry() == null) {
            return NOptional.ofNamedEmpty(name);
        }
        return session.registry().extension(name, type);
    }

    @Override
    public void reportRateLimits(NaruProviderRateLimitInfo info) {
        session.reportProviderRateLimits(info);
    }

    @Override
    public void notice(NMsg message) {
        // no task history to join: a session-level call has no transcript of its own
    }

    @Override
    public NOptional<NPath> sessionDir() {
        return NOptional.ofNamed(session.sessionDir(), "sessionDir");
    }

    @Override
    public String sessionId() {
        return session.uuid();
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
