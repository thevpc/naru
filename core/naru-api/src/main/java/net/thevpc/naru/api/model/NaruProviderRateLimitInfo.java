package net.thevpc.naru.api.model;

import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;

import java.time.Instant;
import java.util.List;

public interface NaruProviderRateLimitInfo {
    String sessionId();
    String userId();

    String providerName();

    Instant serverTime();
    List<NaruRateLimitBucket> tokenBuckets();
    List<NaruRateLimitBucket> requestBuckets();
    NOptional<String> correlationId();
    NOptional<NDuration> retryAfter();
    // Raw provider-specific extras
    NObjectElement rawProviderInfo();
}
