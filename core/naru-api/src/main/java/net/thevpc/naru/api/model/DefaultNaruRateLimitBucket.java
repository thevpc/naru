package net.thevpc.naru.api.model;

import net.thevpc.nuts.util.NOptional;

import java.time.Instant;

public class DefaultNaruRateLimitBucket implements NaruRateLimitBucket{
    private NaruRateLimitWindow window;
    private Integer limit;
    private Integer remaining;
    private Instant resetTime;

    public DefaultNaruRateLimitBucket(NaruRateLimitWindow window, Integer limit, Integer remaining, Instant resetTime) {
        this.window = window;
        this.limit = limit;
        this.remaining = remaining;
        this.resetTime = resetTime;
    }

    @Override
    public NaruRateLimitWindow getWindow() {
        return window;
    }

    @Override
    public NOptional<Integer> getLimit() {
        return NOptional.ofNamed(limit, "limit");
    }

    @Override
    public NOptional<Integer> getRemaining() {
        return NOptional.ofNamed(remaining, "remaining");
    }

    @Override
    public NOptional<Instant> getResetTime() {
        return NOptional.ofNamed(resetTime, "resetTime");
    }
}
