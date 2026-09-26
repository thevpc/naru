package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;

/**
 * Makes token metering available to a session, without the core knowing what a token costs.
 * <p>
 * The core's only involvement is the {@link NaruSessionUsageListener} seam: it announces what
 * a provider reported, and this extension decides what that means. Removing
 * {@code naru-budget} from the classpath removes {@code /stat} and the accounting with it,
 * and the engine keeps running — it just stops adding up numbers nobody asked for.
 *
 * <h2>Scope</h2>
 * Totals are per session. This used to be per agent, which meant {@code /stat} in one
 * session reported another session's spend: a service keyed only by model and user had no
 * way to tell two sessions apart. Scoping to the session fixes that, at the cost of an
 * agent-wide rollup, which is a different question and would need its own store.
 *
 * <h2>What is not persisted</h2>
 * There is no {@code ext/budget.tson}. Spend belongs to the run that incurred it, and
 * reloading yesterday's totals into today's report would overstate current cost, so a
 * restored session starts at zero and {@link #save} stays at its default of writing nothing.
 */
public class NaruBudgetExtension implements NaruSessionExtension, NaruSessionUsageListener {

    public static final String NAME = "budget";

    private NaruSession session;
    private NaruMeteringService metering;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * The session's token accounting.
     * <p>
     * The accessor and the extension ship in the same jar and are registered together, so
     * the lookup only fails if a caller deliberately removed the extension mid-session.
     */
    public static NaruMeteringService metering(NaruSession session) {
        return session.registry().extension(NAME, NaruBudgetExtension.class)
                .map(NaruBudgetExtension::metering)
                .orElseThrow(() -> new IllegalStateException(
                        "the budget extension is not installed in this session"));
    }

    NaruMeteringService metering() {
        if (metering == null) {
            throw new IllegalStateException("metering accessed before the extension was opened");
        }
        return metering;
    }

    @Override
    public void open(NaruSession session) {
        // Idempotent on purpose. A listener registered twice would report every call twice,
        // and a doubled total is a worse bug than a missing one.
        if (this.session == session && metering != null) {
            return;
        }
        this.session = session;
        this.metering = new NaruMeteringServiceImpl(session);
        session.addUsageListener(this);
    }

    @Override
    public void onModelCall(NaruModelKey model, long promptTokens, long completionTokens,
                            long cacheWriteTokens, long cacheReadTokens, NDuration duration) {
        if (metering == null) {
            return;
        }
        metering.trackTransaction(new NaruTokenTransaction(
                null,
                null,
                new NaruModelConfig(model),
                promptTokens,
                completionTokens,
                // passed through as reported, including the -1 that means "this provider
                // does not do cache accounting"; the service normalises that to zero so it
                // cannot run a running total backwards
                cacheWriteTokens,
                cacheReadTokens,
                Instant.now(),
                duration
        ));
    }

    @Override
    public void onProviderRateLimits(NaruProviderRateLimitInfo info) {
        if (metering != null) {
            metering.trackProviderStats(info);
        }
    }

    @Override
    public void close() {
        // Note: the core does not currently call this on session shutdown, so it is a
        // safety net rather than the normal path. The listener list dies with the session
        // anyway; what this protects against is the service outliving the extension and
        // being written to after the fact.
        if (session != null) {
            session.removeUsageListener(this);
        }
        this.session = null;
        this.metering = null;
    }
}