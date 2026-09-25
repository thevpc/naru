package net.thevpc.naru.api.model;

/**
 * How a provider exposes prompt caching, which determines what a caching client
 * must do to get a cache hit.
 *
 * <p>These are genuinely different mechanisms, not variations of one. The mode
 * decides both the wire format and how invalidation is computed, so it is
 * modelled explicitly rather than inferred from the provider name.
 */
public enum NaruCachingMode {

    /**
     * No caching concept at the API level. The full context is re-sent and
     * re-billed every turn. Local providers (Ollama) land here: the server may
     * reuse a KV cache internally per connection, but there is nothing the
     * client can address or rely on.
     */
    NONE,

    /**
     * The provider caches a literal byte-prefix of the request transparently,
     * with no client-side breakpoint API. NARU's only job is to keep the prefix
     * byte-identical across turns; the provider does the rest. Most
     * OpenAI-compatible backends behave this way.
     *
     * <p>Consequence: because the match is positional, a mid-history edit
     * invalidates everything from the edit point onward even though the
     * segments themselves are unchanged. That cost is inherent to the
     * mechanism, not a defect in NARU.
     */
    AUTOMATIC_PREFIX,

    /**
     * The provider accepts inline cache-control breakpoints that the caller
     * places on specific content blocks (Anthropic {@code cache_control}).
     * NARU decides where the breakpoints go; the request is otherwise
     * unchanged. Each call is stateless, so history mutation needs no
     * server-side state management beyond re-placing the breakpoints.
     */
    EXPLICIT_INLINE,

    /**
     * The provider caches context in a separately created, immutable,
     * server-side resource with its own lifecycle and TTL (Gemini
     * {@code CachedContent}), referenced by id from generation requests.
     *
     * <p>This is the most demanding mode: the resource cannot be patched, so a
     * divergence point that falls inside an already-cached prefix forces the
     * whole resource to be discarded and recreated.
     */
    EXPLICIT_RESOURCE;

    /**
     * Whether a cache hit is expressed by the caller sending a marker, as
     * opposed to happening implicitly for a matching prefix. Inline modes must
     * do real work at serialization time; automatic and {@code NONE} do not.
     */
    public boolean isClientControlled() {
        return this == EXPLICIT_INLINE || this == EXPLICIT_RESOURCE;
    }

    /**
     * Whether this mode keeps server-side state that must be tracked, expired
     * and eventually deleted. Only resource mode does.
     */
    public boolean isStateful() {
        return this == EXPLICIT_RESOURCE;
    }
}
