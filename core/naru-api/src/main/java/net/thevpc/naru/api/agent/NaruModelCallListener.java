package net.thevpc.naru.api.agent;

import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.time.NDuration;

/**
 * Notified when a model call is made, providing access to the full request and response.
 * <p>
 * This is a seam for debugging, auditing, and inspection tools. The core announces the
 * structured request and response exactly once per call, without requiring observers to
 * wrap the provider or protocol.
 * <p>
 * A listener that throws is ignored: inspection must never be able to fail a model call.
 * Listeners are held per session and are not persisted.
 */
public interface NaruModelCallListener {

    /**
     * A model call completed. The request and response are the structured objects used
     * by the engine; their JSON representations correspond to what was sent to and
     * received from the provider (modulo provider-specific wire formatting such as
     * caching headers).
     *
     * @param task        the task that made the call; never null on the production path
     * @param model       the model key used for the call
     * @param request     the request sent to the model (messages, tools, env)
     * @param response    the response received from the model (message, token counts, stop reason)
     * @param duration    wall-clock time of the call, for latency reporting
     */
    void onModelCall(NaruTask task,
                     NaruModelKey model,
                     NaruModelRequest request,
                     NaruResponse response,
                     NDuration duration);
}