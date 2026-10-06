package net.thevpc.naru.ext.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruProviderRateLimitInfo;
import net.thevpc.naru.ext.budget.store.NaruBudgetStoreQuery;

import java.math.BigDecimal;
import java.util.List;

/**
 * Running totals of token spend, per model and per user.
 * <p>
 * Owned by {@link NaruBudgetExtension}, so one instance serves exactly one session and does
 * not need the session passed to every call. Obtain it with
 * {@link NaruBudgetExtension#budget(NaruSession)}.
 */
public interface NaruBudgetService {
    /**
     * Folds one completed model call into the running totals for the model, both for the
     * call's own user and for the model overall.
     */
    void trackTransaction(NaruTokenTransaction transaction);

    /**
     * Totals for one model, or null-safe zeroes when nothing has been recorded.
     *
     * @param user may be null, meaning "all users"
     */
    NaruModelBudgetStats findModelBudgetStats(NaruModelKey model, String user, Labels labels);

    NaruModelBudgetStats findModelBudgetStats(NaruBudgetStoreQuery query);

    /**
     * Totals for every model with at least one recorded call. One row per model, with users
     * already folded in.
     */
    List<NaruModelBudgetStats> findByModelBudgetStats(NaruBudgetStoreQuery query);

    /**
     * Records a provider's self-reported rate limits, replacing any previous report from
     * that provider for this session.
     */
    void trackProviderStats(NaruProviderRateLimitInfo stats);

    /**
     * The most recent rate-limit report per provider for this session.
     */
    List<NaruProviderRateLimitInfo> findProviderRateLimitInfos();
}
