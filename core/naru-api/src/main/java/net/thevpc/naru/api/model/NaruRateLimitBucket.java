package net.thevpc.naru.api.model;

import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;

public interface NaruRateLimitBucket {
    NaruRateLimitWindow getWindow();
    NOptional<Integer> getLimit();
    NOptional<Integer> getRemaining();
    NOptional<Instant> getResetTime();
}
