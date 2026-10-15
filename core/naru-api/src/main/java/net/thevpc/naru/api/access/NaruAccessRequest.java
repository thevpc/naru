package net.thevpc.naru.api.access;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One question put to the policy: may <em>this actor</em> perform <em>this
 * action</em> on <em>this resource</em>, here and now?
 *
 * <p>This is a request and not a bare triple of arguments because a decision can
 * depend on context that is not part of the resource: the task (for resolving
 * project-relative patterns), the session, anything a domain attaches through
 * {@link #context(String, Object)}. Bundling it keeps {@code check} one method
 * while leaving room to grow.
 *
 * <p>The request is immutable; {@code task(...)} and {@code context(...)} return
 * a copy, so a request handed to a listener can be inspected long after the call
 * that produced it.
 */
public final class NaruAccessRequest {

    private final NaruResource resource;
    private final NaruAction action;
    private final NaruActor actor;
    private final NaruTask task;
    private final Map<String, Object> context;

    private NaruAccessRequest(NaruResource resource, NaruAction action, NaruActor actor,
                              NaruTask task, Map<String, Object> context) {
        if (resource == null || action == null) {
            throw new NIllegalArgumentException(NMsg.ofC("access request requires a resource and an action"));
        }
        this.resource = resource;
        this.action = action;
        this.actor = actor;
        this.task = task;
        this.context = context == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    public static NaruAccessRequest of(NaruResource resource, NaruAction action, NaruActor actor) {
        return new NaruAccessRequest(resource, action, actor, null, null);
    }

    public NaruAccessRequest task(NaruTask task) {
        return new NaruAccessRequest(resource, action, actor, task, context);
    }

    public NaruAccessRequest context(String key, Object value) {
        Map<String, Object> c = new LinkedHashMap<>(context);
        if (value == null) {
            c.remove(key);
        } else {
            c.put(key, value);
        }
        return new NaruAccessRequest(resource, action, actor, task, c);
    }

    public NaruResource resource() {
        return resource;
    }

    public NaruAction action() {
        return action;
    }

    public NaruActor actor() {
        return actor;
    }

    public NaruTask task() {
        return task;
    }

    public Map<String, Object> context() {
        return context;
    }

    @Override
    public String toString() {
        return (actor == null ? "?" : actor.toString()) + " " + action.id() + " " + resource;
    }
}
