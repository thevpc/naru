package net.thevpc.naru.api.budget;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.nuts.util.NOptional;

import java.math.BigDecimal;
import java.util.List;

public interface NaruMeteringService {
    void trackTransaction(NaruTokenTransaction naruTokenTransaction, NaruSession session);

    /**
     *
     * @param model
     * @param user  can be null
     * @return
     */
    NaruModelStats findModelStats(NaruModelKey model, String user, NaruSession session);

    List<NaruModelStats> findModelStats(NaruSession session);

    void setUnitPrice(NaruModelKey model, BigDecimal value, NaruSession session);

    BigDecimal getUnitPrice(NaruModelKey model, NaruSession session);
}
