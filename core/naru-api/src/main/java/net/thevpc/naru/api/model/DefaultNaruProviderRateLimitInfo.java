package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NAssert;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DefaultNaruProviderRateLimitInfo implements NaruProviderRateLimitInfo{
    private String sessionId;
    private String userId;
    private String providerName;
    private Instant instant;
    private List<DefaultNaruRateLimitBucket> tokenBuckets;
    private List<DefaultNaruRateLimitBucket> requestBuckets;
    private String correlationId;
    private NDuration retryAfter;
    // Raw provider-specific extras
    private NObjectElement rawProviderInfo;


    public DefaultNaruProviderRateLimitInfo(String sessionId,String userId,String providerName,Instant instant, List<DefaultNaruRateLimitBucket> tokenBuckets, List<DefaultNaruRateLimitBucket> requestBuckets, String correlationId, NDuration retryAfter, NObjectElement rawProviderInfo) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.providerName = providerName;
        this.instant = NAssert.requireNamedNonNull(instant,"instant");
        this.tokenBuckets = new ArrayList<>(tokenBuckets);
        this.requestBuckets = new ArrayList<>(requestBuckets);
        this.correlationId = correlationId;
        this.retryAfter = retryAfter;
        this.rawProviderInfo = NAssert.requireNamedNonNull(rawProviderInfo,"rawProviderInfo");
    }


    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public String userId() {
        return userId;
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public Instant serverTime() {
        return instant;
    }

    @Override
    public List<NaruRateLimitBucket> tokenBuckets() {
        return Collections.unmodifiableList(tokenBuckets);
    }

    @Override
    public List<NaruRateLimitBucket> requestBuckets() {
        return Collections.unmodifiableList(requestBuckets);
    }

    @Override
    public NOptional<String> correlationId() {
        return NOptional.ofNamed(correlationId,"correlationId");
    }

    @Override
    public NOptional<NDuration> retryAfter() {
        return NOptional.ofNamed(retryAfter,"retryAfter");
    }

    @Override
    public NObjectElement rawProviderInfo() {
        return rawProviderInfo;
    }
}
