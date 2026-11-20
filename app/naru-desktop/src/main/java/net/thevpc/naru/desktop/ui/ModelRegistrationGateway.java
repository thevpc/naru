package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.elem.NElement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the settings pane needs from the registration store, and nothing else.
 *
 * <p>The pane is written against this rather than a {@code NaruSession} for the same reason
 * {@link PlanPane} is written against {@code net.thevpc.naru.api.plan.NaruPlanService}: the desktop
 * test classpath has no extensions on it, and what is worth pinning in a widget test is that a click
 * becomes exactly one call with the right scope and the right edits — not that the engine accepted
 * them, which the engine's own tests cover. {@link SessionModelRegistrations} is the production
 * binding, and it is thin.
 */
interface ModelRegistrationGateway {

    /**
     * False when the store cannot be reached at all — no session is open. An empty listing with this
     * true means a project that simply has no registrations, and the pane says which of the two.
     */
    boolean isAvailable();

    /** Every registration the live session can use, by id; empty when no session is open. */
    Map<String, NaruModelRegistration> registrations();

    /** Which layer owns each field of one id, or null when nothing declares it. */
    NaruRegistrationOrigin origin(String id);

    /**
     * Merge {@code edits} into the stored registration and validate the result. A null value clears
     * that parameter.
     *
     * @param update true when the id must already exist
     * @return null when the write landed, otherwise the problem to show the reader
     */
    String save(String id, Map<String, NElement> edits, boolean update, NaruRegistrationScope scope);

    /**
     * Delete one id from one scope's files.
     *
     * @return null when it landed, otherwise the line to show the reader: that scope declared nothing,
     *         or the store refused the write
     */
    String remove(String id, NaruRegistrationScope scope);

    /** The provider types a {@code provider} param may name. */
    List<String> providerTypes();

    /** The wire protocols one provider type speaks. */
    Set<String> protocols(String providerType);

    /**
     * The model ids an endpoint reports for its picker, or empty when it cannot enumerate any
     * (a generic endpoint, an unreachable server, a provider with no key yet).
     *
     * @param registrationId the instance being edited, so a registration with its own url/key
     *                       lists its own models; null while a new id is being typed
     * @param providerType   the provider type whose listing supplies the candidates
     */
    List<String> models(String registrationId, String providerType);
}
