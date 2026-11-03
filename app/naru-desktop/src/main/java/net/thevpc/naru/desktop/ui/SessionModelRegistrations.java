package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruModelRegistrationRules;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.elem.NElement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The gateway bound to a live session.
 *
 * <p>Everything the pane can do here is exactly what {@code /model add|update|remove} does, through
 * the same {@link NaruModelRegistrationRules} — one validator for both hosts, so a form can never
 * accept an endpoint the command line would refuse. The session is supplied, not captured: the
 * settings surface outlives the session it was opened from, and a pane bound to a stopped session
 * would keep showing a store it can no longer write.
 */
final class SessionModelRegistrations implements ModelRegistrationGateway {

    private final Supplier<NaruSession> sessionSupplier;

    SessionModelRegistrations(Supplier<NaruSession> sessionSupplier) {
        this.sessionSupplier = sessionSupplier;
    }

    private NaruSession session() {
        try {
            return sessionSupplier == null ? null : sessionSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return session() != null;
    }

    @Override
    public Map<String, NaruModelRegistration> registrations() {
        NaruSession s = session();
        if (s == null) {
            return Map.of();
        }
        try {
            return s.registrations();
        } catch (RuntimeException e) {
            // a hand-edited file that fails to parse must leave the pane readable, not empty of
            // everything with an error the reader cannot locate
            return Map.of();
        }
    }

    @Override
    public NaruRegistrationOrigin origin(String id) {
        NaruSession s = session();
        if (s == null) {
            return null;
        }
        try {
            return s.registrationOrigin(id).orNull();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String save(String id, Map<String, NElement> edits, boolean update, NaruRegistrationScope scope) {
        NaruSession s = session();
        if (s == null) {
            return "no session is open — registrations are edited through a session's project";
        }
        NaruModelRegistrationRules.Outcome outcome =
                NaruModelRegistrationRules.apply(s, id, edits, update, scope);
        if (!outcome.isOk()) {
            return outcome.problem();
        }
        try {
            s.putRegistration(outcome.registration(), scope);
        } catch (RuntimeException e) {
            // the form has already promised "not saved: …", so a store that refuses the write —
            // a read-only file, a directory it cannot create — has to answer in that line rather
            // than throw on the toolkit thread
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.toString() : message;
        }
        return null;
    }

    @Override
    public String remove(String id, NaruRegistrationScope scope) {
        NaruSession s = session();
        if (s == null) {
            return "no session is open — registrations are edited through a session's project";
        }
        try {
            return s.removeRegistration(id, scope) ? null
                    : "the " + scope.word() + " files do not declare '" + id + "'";
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.toString() : message;
        }
    }

    @Override
    public List<String> providerTypes() {
        NaruSession s = session();
        if (s == null) {
            return List.of();
        }
        try {
            return NaruModelRegistrationRules.providerTypes(s);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public Set<String> protocols(String providerType) {
        NaruSession s = session();
        if (s == null) {
            return Set.of();
        }
        try {
            return NaruModelRegistrationRules.supportedProtocols(s, providerType);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }
}
