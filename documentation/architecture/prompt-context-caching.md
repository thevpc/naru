# Prompt and context caching

How NARU reuses provider-side prompt caches: what is cached, how invalidation is
decided, and what each provider actually does with the result.

## The problem

A long agent session re-sends the same prefix on every model call: the system
prompt, the agent's own instructions, indexed project context, tool definitions,
and the entire conversation so far. With a 40-turn session that is the same
dozen thousand tokens billed again on every call, and it is the dominant cost of
running an agent at all.

Providers will cache a request prefix for you — they just need to be told, or to
be able to recognise the pattern themselves. NARU's job is to know *what* is
safe to keep and *when* the kept copy is no longer valid.

## The one hard requirement

**A cacheable segment must never change after it has been sent.**

This single constraint drives the whole design. If a segment's content changes
after the provider has cached a prefix containing it, that prefix is dead — and
the next call pays full price for it. So the interesting question is not "how do
we hash the context" but "how do we cut the context into pieces that are frozen
once created".

That is why the conversation is cut **per turn**. Everything before the current
turn is immutable; only the turn in flight is still growing.

## Segments

A request may carry a `NaruCacheableContext`: an ordered list of
`NaruContextSegment`, each holding either messages or tool definitions.

| Segment in `NaruTaskImpl` | Content | Changes when |
|---|---|---|
| `tool-defs` | tool definitions | the available tool set changes |
| `system-context` | system prompt, extension contributions, agent classpath, skills | configuration or agent files change |
| `turn-N` | one completed user turn | never (a closed turn is immutable) |
| `turn-N` (last) | the turn in flight | on every message, until the turn ends |

The last turn is marked **volatile**, which forbids placing a cache breakpoint on
it. Caching content that is about to change buys nothing and costs a breakpoint
slot.

Segment boundaries are recorded durably on the messages themselves
(`NaruMessage.turnBoundary`), not inferred from positions. They survive a session
reload, and they are set only where a user turn genuinely begins — the typed-input
path — so agent-generated user-role messages do not fragment the conversation
into segments too small to be worth caching.

### Order is the provider's order, not the builder's

Segments are laid out **tools first, then system, then the conversation**. This
is not cosmetic.

Anthropic evaluates a request prefix as tools → system → messages, regardless of
the order the fields appear in the JSON. "This segment changed" therefore only
implies "everything after it *in wire order* is suspect" if the segment list
follows wire order. With tools last, changing the tool set would look like a
full hit on the conversation, and NARU would cheerfully claim a cache the
provider had already dropped — a silent, recurring overcharge that looks like
caching working.

The Anthropic serializer resolves markers against wire position for the same
reason, and has a test for the out-of-order case.

## Identity: a hash chain, not a set of hashes

Each segment gets a key:

```
key[0] = SHA-256(canonical(segment[0]))
key[i] = SHA-256(key[i-1] ‖ 0x00 ‖ canonical(segment[i]))
```

Then the longest common **leading** run of keys is the still-valid prefix.

Chaining is not an optimisation, it is a correctness requirement. NARU permits
mid-history edits — the user can reword an old question, a tool result can be
truncated. A segment with an unchanged hash can still sit at a *different
position*, and position is what determines whether the provider's cached prefix
still matches. Independent per-segment hashes cannot detect a move; a chain
cannot be fooled by one.

`canonical()` covers exactly what the provider receives: message roles, content,
tool names, call ids, arguments (sorted), images, thinking. It deliberately
excludes:

- **`id`** — a label for diagnostics and for pairing against persisted state.
  Hashing it would mean a caller who derives ids dynamically misses on every
  turn while the bytes on the wire are identical.
- **`source` / `sourceName`** — bookkeeping, not content.
- **`turnBoundary`** — describes grouping, not content.
- **`cacheable` / `minLifetime`** — policy, applied when choosing breakpoints,
  not part of the payload.

Excluding these cannot cause a false hit, because what remains is exactly what
gets sent.

## Caching modes

`NaruModelCapabilities.cachingMode()` declares what a provider supports.

| Mode | Meaning | Providers |
|---|---|---|
| `NONE` | nothing to do | Ollama |
| `AUTOMATIC_PREFIX` | server caches a repeated prefix on its own; NARU only reports usage | Groq, Cerebras, Mistral, xAI, GitHub Models, OpenRouter, Colibri, Gemini (OpenAI-compat route) |
| `EXPLICIT_INLINE` | NARU places `cache_control` breakpoints | Anthropic, `custom` endpoints of type `anthropic` |
| `EXPLICIT_RESOURCE` | cache lives in a separate server-side resource | native Gemini (`type=gemini`) — wire protocol only, see gaps |

An unrecognised configured mode resolves to `NONE` rather than a guess. Guessing
could send a request shape the user's server rejects.

`NONE` is a no-op everywhere: the emitted body is byte-identical to what the
same request produced before caching existed, and there is a test asserting
exactly that.

## State

A `NaruModelCacheExtension` in `naru-ai-models` owns per-session state, stored at
`<session>/ext/model-cache.tson`. Entries are keyed by **provider + model +
mode**; a session that talks to several models would otherwise have one model's
cache identity silently invalidate another's.

Everything about this is defensive, because the failure modes are asymmetric:

- a **false miss** costs tokens and a slightly slower turn;
- a **false hit** costs tokens *and* produces a wrong answer the user cannot see.

So: an absent extension, an unknown mode, a model that changed underneath us, a
corrupt state file, an expired resource — every one of them resolves to "send
the whole thing". A corrupt cache hint must never cost the user their session;
the only acceptable degradation is a cold cache.

State is committed **only after a successful response**. Committing earlier would
record a prefix the provider never actually stored, and the next turn would
trust a lie.

Resource-backed caching additionally stops trusting an id inside a safety margin
before its stated expiry — a turn can take seconds, and starting on a resource
that dies mid-flight wastes the whole turn.

## Where it hooks in

`NaruModelProtocolBase.chat()` plans once, before serialising, and reuses the
same body for every retry. A retry must re-send the *identical* prefix; planning
freshly per attempt would invalidate the very thing it is retrying.

Providers that do not implement the optional `NaruCacheAwareRequestSerializer`
degrade silently to uncached-but-working behaviour, rather than erroring.

## Token accounting

Cache tokens are a **breakdown of input tokens, not an addition to them**, and the
two providers disagree about it:

- **OpenAI** reports `prompt_tokens_details.cached_tokens` *inside*
  `prompt_tokens`. Anything not served from cache was processed at full rate, so
  it is counted as a cache write.
- **Anthropic** reports `input_tokens` *excluding* both cache reads and writes.
  The billed input total is the sum of all three.

Conflating these under-reports spend, so each parser follows its own provider's
convention. Providers that report nothing leave cache tokens unset (`-1`) rather
than recording a misleading zero, and an unset value is treated as zero when
accumulating so it cannot drag a running total backwards.

## Gemini: two protocols, not one

`NaruGeminiProvider` speaks Google's **OpenAI-compatible** route
(`/v1beta/openai/...`), where caching is `AUTOMATIC_PREFIX`. A native
`gemini` protocol type is also registered
(`custom.endpoints.<name>.type=gemini`, or programmatically via
`NaruModelProtocolTypes.register`), speaking the first-class `generateContent`
API.

The native route exists because resource caching is impossible on the
compatibility layer: it offers no way to create or reference a `CachedContent`
resource, so it can only ever do automatic prefix caching. The wire translation
is complete and fixture-tested — `systemInstruction` instead of a system role,
`model` instead of `assistant`, function declarations grouped under a single
`tools[]` entry, and `functionResponse` parts on a user turn — with the model in
the path (`models/{model}:generateContent`), which is why
`NaruModelProtocolBase` grew an overridable `chatPath(task, env)`.

## Known gaps

- **The `CachedContent` resource lifecycle is not implemented.** The native
  Gemini wire protocol exists and is tested, and `NaruCacheBaseline` carries the
  resource id, expiry and usability logic (also tested), but nothing creates,
  refreshes or deletes a real `CachedContent` resource, and `EXPLICIT_RESOURCE`
  is not yet selected by any provider. Until that lands, Gemini behaves as
  `AUTOMATIC_PREFIX`.
- **No history-edit events.** Invalidation is computed by re-diffing the segment
  chain on every call, which is correct but does O(segments) of hashing per turn.
  An explicit event would let it skip untouched prefixes.
- **No `/stats` surface for cache ratios.** The counters are tracked and exposed
  on `NaruModelStats.getCacheHitRatio()`, but nothing displays them yet.
- **Metering had a pre-existing bug**, fixed in the same change:
  `NaruMeteringServiceImpl.accumulate` assigned instead of added, so a model that
  had served ten calls reported the token counts of the tenth alone. This is
  invisible in a single-call test, which is why it survived; the regression test
  deliberately uses three.
