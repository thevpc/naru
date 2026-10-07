# Model registrations

> **When to use this:** you want to *register* a model — bind a provider (gemini,
> ollama, an OpenAI-compatible server, ...) to an identity you choose, with its own
> API key (yours, a teammate's, a different project's), its own URL, and its own
> default parameters. Registering the same provider several times under different
> names is the point: `personal` and `work` can both be Gemini, with different keys,
> and they never step on each other.
>
> If you only ever run one local Ollama with default settings, you never need this —
> the built-in providers already show up in `/model list`.

---

## Table of contents

- [1. Available vs registered](#1-available-vs-registered)
- [2. Quick start: two keys for one provider](#2-quick-start-two-keys-for-one-provider)
- [3. Addressing and identity](#3-addressing-and-identity)
- [4. The `/model` directive](#4-the-model-directive)
- [5. Storage and visibility](#5-storage-and-visibility)
- [6. Config resolution order](#6-config-resolution-order)
- [7. Per-registration parameters](#7-per-registration-parameters)
- [8. Wire types: `openai`, `anthropic`, `gemini`](#8-wire-types-openai-anthropic-gemini)
- [9. Availability, probing, and listings](#9-availability-probing-and-listings)
- [10. What was removed, and what replaces it](#10-what-was-removed-and-what-replaces-it)
- [11. Provider API: `type()` and `newInstance()`](#11-provider-api-type-and-newinstance)
- [12. Recap](#12-recap)

---

## 1. Available vs registered

Two different questions, two different things:

| Term | Meaning | Where it comes from |
|------|---------|---------------------|
| **Available model** | A model NARU can actually call *right now*: enumerated by a reachable provider, with a usable API key | The catalog: `/model list` |
| **Registration** | A named entry you created: "this provider type, addressed as `<id>`, with *this* key/URL/parameters" | `.naru/config/registrations.tson`: `/model registered` |

A registration *creates* available models: once you register `personal` on the
gemini provider, every model gemini reports appears a second time as
`personal/<model>` — callable with `personal`'s key, accounted separately, and
independent of the plain `gemini/<model>` entry (which keeps using `gemini.apiKey`
or `GEMINI_API_KEY`).

Names are **instance ids**, not provider types. The provider *type* is what the
registration points at; it stays reachable for type-scoped behaviour
(`--provider=gemini` matches the base provider and all of its registrations).

## 2. Quick start: two keys for one provider

`GEMINI_KEY_A` and `GEMINI_KEY_B` are already exported in the environment:

```text
/model add personal --provider=gemini --apiKey=$GEMINI_KEY_A --temperature=0.2
/model add work     --provider=gemini --apiKey=$GEMINI_KEY_B
/model registered
```

```text
Registrations: 2
  personal  gemini  models=auto  temperature=0.2  apiKey=$GEMINI_KEY_A
  work      gemini  models=auto  apiKey=$GEMINI_KEY_B
```

```text
/model list
```

```text
  (*)[01] personal/gemini-3.8-flash (gemini) [1M]
      [02] work/gemini-3.8-flash (gemini) [1M]
      [03] gemini/gemini-3.8-flash [1M]
      ...
```

```text
/model use personal/gemini-3.8-flash
/model use work/gemini-3.8-flash
```

Each `/model use` switches to a different key without touching any config file.

A registration that pins one model:

```text
/model add fast --provider=gemini --model=gemini-3.8-flash --apiKey=$GEMINI_KEY_A
/model use fast            ← bare id works: the registration pins a single model
```

## 3. Addressing and identity

A model key is always `<instance id>/<model id>` — two segments, no exceptions:

| Key | Instance id | Provider type | API key used |
|-----|-------------|---------------|--------------|
| `gemini/gemini-3.8-flash` | `gemini` (built-in) | `gemini` | `gemini.apiKey` → `GEMINI_API_KEY` |
| `personal/gemini-3.8-flash` | `personal` (registration) | `gemini` | registration's key → `$GEMINI_KEY_A` |
| `cpt/gpt-4o` | `cpt` (registration) | `openai` | registration's key → `OPENAI_API_KEY` |
| `litellm/qwen3-32b` | `litellm` (registration) | `custom` (protocol `openai`) | registration's key |
| `gpu/llama3.1:8b` | `gpu` (registration) | `ollama` | registration's `url` |

Selection accepts, in this order:

1. a registration id (`fast`) — when it pins a single model, otherwise an error
   listing that registration's models;
2. a full key (`personal/gemini-3.8-flash`);
3. a bare model id (`gemini-3.8-flash`) — first match in the catalog;
4. a positional index from the last `/model list`.

## 4. The `/model` directive

| Command | Effect |
|---------|--------|
| `/model add <id> --provider=<type> [--protocol=<wire>] [--model=<id>\|--models=a,b] [--url=…] [--apiKey=sk-…\|$VAR] [--temperature=… --contextLength=… --maxTokens=… …]` | Create (or, with an existing id, update) a registration |
| `/model add <id> --protocol=<wire> --url=… --models=a,b [--apiKey=…]` | Same, without a provider type: a generic HTTP endpoint speaking the wire (§8) |
| `/model remove <id>` | Delete a registration and its stored parameters |
| `/model registered` | Table of registrations, keys **masked** (`sk-***abcd`) |
| `/model list [<filter>] [--provider=<type>] [--free]` | Merged listing of every selectable model (built-ins + registrations) |
| `/model use <id\|key\|index>` | Select a model |
| `/model current` | Show the selected model |
| `/model use-global <…>` | Select and persist as the session default |
| `/model update <id> [--temperature=… --apiKey=…]` | Change a registration's parameters |

`--provider=` filters by **type**, so `/model list --provider=gemini` shows the
base provider and every gemini registration together.

Autocomplete covers the whole surface: subcommand names, then per-subcommand
arguments — registration ids for `add`/`update`/`remove`, model references
(indexes, ids, `provider/model` keys) for `use`/`use-global` and the bare form,
and `--param=value` flags with their vocabulary (`--provider=`, `--protocol=`,
`--models=` of the provider chosen in the line, booleans).

Removed commands (see §10 for what replaced them):
`/model alias`, `/model unalias`, `/model install`, `/model uninstall`,
`/model ps`, `/model unload`, `/model endpoint`.

## 5. Storage and visibility

Registrations live in two files on the same visibility axis as `env.tson`:

```text
.naru/config/registrations.tson         ← public, checked in: structure, params, env references
.naru/local/config/registrations.tson   ← private, gitignored: literal secrets
```

```tson
{
  personal: {
    provider: "gemini",
    apiKey: "$GEMINI_KEY_A",        // interpolated at request time
    temperature: 0.2
  },
  work:     { provider: "gemini", apiKey: "$GEMINI_KEY_B" },
  fast:     { provider: "gemini", model: "gemini-3.8-flash", apiKey: "$GEMINI_KEY_A" },
  litellm:  { protocol: "openai", url: "http://localhost:4000/v1", models: "qwen3-32b" },
  gpu:      { provider: "ollama", url: "http://gpu-box:11434" }
}
```

Rules:

- Any **string** value may contain `$NAME` / `${NAME}` placeholders — the same
  interpolation `NMsg.ofV(...)` uses. Placeholders are resolved when a request is
  built, so rotating an exported key needs no re-registration.
- A value containing `$NAME`/`${NAME}` references holds no secret, so it goes to
  the **public** file; a literal secret (e.g. `--apiKey=sk-…`) goes to the
  **private** file, and plain parameters are public. `/model registered` never
  prints a literal key, only its mask.
- Omitting `apiKey` entirely is valid: resolution falls through to the environment
  (§6), which is how a registration "finds its key by itself".

Both files are plain TSON — hand-editable, and the directive writes the same thing
you would. A generic endpoint is written with `protocol` and **no** `provider`
(the internal `custom` type is implied: `provider()` reports `custom`, but no
`provider` param is ever stored). Writing the internal `custom` type — or a wire
id that has no provider type — as a `provider` is rejected outright:
`provider: "custom"` and `provider: "anthropic"` can only mean a generic wire
from §8, and a hand-edited file must fail loudly. `openai` is a built-in
provider type now, so `provider: "openai"` is valid and registers OpenAI's own
API. The pre-rename spellings are rejected with hints: `provider: "openapi"`
(→ `openai` is now both a wire id and a provider type) and
`provider: "wire"` (→ the internal type is `custom`). `gemini` is deliberately
not among the rejected ids: as a provider it means the built-in gemini provider,
and its native wire shape is selected with `protocol: "gemini"` on top of it (§8).

## 6. Config resolution order

For any parameter of a registration (`apiKey`, `url`, timeouts, retries, ...):

1. **the registration's own value** — wins; `$VAR` references inside it are
   resolved against the layered env below, at request time;
2. **the layered env under `<instance id>.<param>`** — searched **session env
   first, then agent env, then the system environment**, so the most specific
   scope wins: `personal.apiKey` from a value set in the running session, else
   from `env.tson` / `/settings`, else from an exported variable;
3. **the type's default** — `GEMINI_API_KEY` for gemini, the provider's built-in
   base URL, default timeouts. Default env names go through the same layered
   env, so `GEMINI_API_KEY` may come from any of the three scopes — an exported
   variable is simply the last resort.

Step 3 is why `/model add mygem --provider=gemini` with no `--apiKey` still works
when `GEMINI_API_KEY` is exported: the registration inherits the provider's normal
key lookup.

The same three layers back every step, including `$VAR` interpolation, so a key
rotated in any scope takes effect on the next request — no re-registration, no
file edit. A blank value in a layer counts as unset: the lookup falls through to
the next layer instead of locking the name out.

## 7. Per-registration parameters

A registration carries any parameter a model config can carry — this is what
replaces aliases:

| Parameter | Flag on `/model add` and `/model update` |
|-----------|------------------------------------------|
| pinned model / model subset | `--model=` / `--models=` |
| API key | `--apiKey=` |
| base URL | `--url=` |
| `temperature` | `--temperature=` |
| `contextLength` | `--contextLength=` |
| `nucleusThreshold` (top_p) | `--nucleusThreshold=` |
| `candidateCount` (top_k) | `--candidateCount=` |
| `maxTokens` | `--maxTokens=` |
| stop sequences | `--stop=` (repeatable) |
| reasoning delimiters | `--thinkingTags=` |
| chat path (wire types) | `--chatPath=` |
| native tool calling (wire types) | `--tools=true\|false` |
| reachability probe (wire types) | `--probe=true\|false` |
| hide from listings | `--enabled=false` |

Timeouts and retries follow the same resolution order (§6) under the instance id:

| Key | Default |
|-----|---------|
| `<id>.connectTimeout` | `120s` |
| `<id>.readTimeout` | `120s` |
| `<id>.timeout` | alias for both |
| `<id>.maxRetries` | `5` (fallback `model.maxRetries`) |
| `<id>.retryPeriod` | `2s` exponential backoff (fallback `model.retryPeriod`) |

Values are merged into every model selected under that instance id, so
`/model use personal/gemini-3.8-flash` selects *personal's* configuration —
including its key — not the base provider's.

## 8. Wire types: `openai`, `anthropic`, `gemini`

A registration has two independent knobs:

| Knob | Meaning |
|------|---------|
| `--provider=<type>` | **Who** serves the model: enumerates its models, resolves capabilities, provides the default key. A built-in provider type (`gemini`, `openai`, `ollama`, `openrouter`, ...) or the generic `custom` type for an endpoint with no provider class (§8). |
| `--protocol=<wire>` | **How** the request is shaped: `openai`, `anthropic`, or `gemini` (native `generateContent`). Optional — defaults to the provider's own wire shape. |

The two knobs are orthogonal: `--provider` picks *who*, `--protocol` picks *how*,
and a registration only needs the one it variations on.

Pointing NARU at an HTTP endpoint with no built-in provider class is a generic
`custom` registration — `--protocol` alone, no `--provider=`:

```text
/model add litellm  --protocol=openai    --url=http://localhost:4000/v1 --models=qwen3-32b --apiKey=$LITELLM_KEY       ← generic endpoint, no provider class
/model add claude2  --protocol=anthropic --url=https://api.anthropic.com --models=claude-sonnet-4 --apiKey=$ANTHROPIC_KEY
/model add cpt      --provider=openai    [--url=http://myproxy:4000/v1]  --apiKey=$OPENAI_API_KEY                   ← OpenAI's own API, --url= re-points it
/model use litellm/qwen3-32b
```

- `openai` — OpenAI-compatible `POST {url}/chat/completions`, sending
  `Authorization: Bearer <key>` only when a key resolves
- `anthropic` — Anthropic Messages `POST {url}/v1/messages` with `x-api-key` +
  `anthropic-version` headers and the Messages body shape (`system` hoisted to top
  level, required `max_tokens`, `tool_use`/`tool_result` content blocks)
- `gemini` — Google's native `POST {url}/models/{model}:generateContent`

A generic `custom` registration exposes only the models you declare with
`--models=`/`--model=` (there is no provider class to list them for you), and no
`--models` means nothing to list. Wire types are ordinary registration parameters
(§7): `url`, `models`, `chatPath`, `contextLength`, `tools`, `probe`, and the
timeout/retry keys. An endpoint with no `--apiKey` is fine too (`Authorization` is
simply omitted).

`--protocol` also **overrides a built-in provider's** wire shape — the one case
that matters today is speaking native Gemini against Google's API (required for
`CachedContent` resource caching, which the OpenAI-compatible route cannot do):

```text
/model add native --provider=gemini --protocol=gemini --apiKey=$GEMINI_KEY_A
```

Wire ids are what the providers themselves declare, not a central registry: every
provider has a `defaultProtocol()` — the wire it speaks when none is configured —
and a `supportedProtocols()` set — its own default plus any override shapes it has
code for (§11). The generic `custom` type declares `openai`, `anthropic` and
`gemini`; a dialect provider (`mistral`, `openrouter`) declares its own id plus
`openai`; `ollama` declares none, so its single `api/chat` shape cannot be
re-pointed. Unknown `--protocol` values are **rejected** by `/model add`; a wire
id the provider does not declare is *ignored with a warning* at runtime (its
default wire is used) — never silently substituted, never fatal.

There is no second mechanism for this any more: `/model endpoint` and the
`custom.endpoints.*` environment keys are **gone**, and `custom/<endpoint>/<model>`
addresses nothing.

## 9. Availability, probing, and listings

- A registration contributes models only when it can be used: its API key resolves
  (§6) and, when it targets a custom URL, passes the reachability probe. The probe
  is a short-timeout `GET` on the base URL (3 s connect/read, 5 s TTL cache); any
  HTTP response counts as reachable, and `probe=false` opts out entirely. No key →
  no models → the instance simply doesn't appear.
- `/model list` is the merged, single list: base providers and registrations
  together, annotated with their type when the instance id differs from it.
  Indexes printed there are what `/model use <n>` resolves against, exactly as
  before.
- Two registrations of the same provider may legitimately report **different model
  sets** — each enumerates with its own key.
- Live model enumeration is cached per (`type()`, wire protocol, url, api key),
  so five gemini registrations sharing a key and endpoint cost one lookup, not
  five.

## 10. What was removed, and what replaces it

Everything below is a **clean cut**: no import, no compat reads — old config files
keep working for nothing, you re-create what you need with `/model add`.

**Aliases are dropped.** `/model alias`, `/model unalias` and the alias form of
`/model update` are gone, and `aliases.tson` (both `.naru/model/aliases.tson` and
the legacy `.naru/models/aliases.tson`) is no longer read: delete it. A
registration id *is* the name now, and it can carry configuration — which an alias
never could (an alias was only selectable if its target already existed in the
catalog, and could not hold an API key):

```text
// was: seek = ollama/deepseek-coder-v2:16b, contextLength=163840
/model add seek --provider=ollama --model=deepseek-coder-v2:16b --contextLength=163840
/model use seek            ← bare id works (§3)
```

**Custom endpoints are dropped.** `/model endpoint add|remove|list`, the
`custom.endpoints.*` environment keys and the `custom/<endpoint>/<model>` address
are gone; wire-type registrations (§8) are the one concept:

```text
// was: /model endpoint add litellm --url=http://localhost:4000/v1 --models=qwen3-32b
/model add litellm --protocol=openai --url=http://localhost:4000/v1 --models=qwen3-32b --apiKey=$LITELLM_KEY
```

**Ollama lifecycle commands move to `/ollama`,** where they belong:

| Was | Now |
|-----|-----|
| `/model install <m>` | `/ollama pull <m>` |
| `/model uninstall <m>` | `/ollama rm <m>` |
| `/model ps` | `/ollama ps` |
| `/model unload <m>` | `/ollama unload <m>` |

## 11. Provider API: `type()`, `newInstance()`, and the wire shape

Adding a provider is unchanged — implement `NaruModelProvider` and it is discovered
by SPI. Three additions make registrations and `--protocol` work:

```java
public interface NaruModelProvider {
    String name();                                   // the instance id (unchanged)
    default String type() { return name(); }         // implementation type: "gemini", "ollama", ...
    default NaruModelProvider newInstance(String id) // same implementation, new instance id
    default String defaultProtocol() { return "openai"; }   // the wire spoken when --protocol is unset
    default Set<String> supportedProtocols() { return Set.of(defaultProtocol()); } // override shapes (§8)
}
```

The two protocol members are the whole wire decision: `defaultProtocol()` is the
provider's own wire id (a dialect provider returns `mistral`, `openrouter` or
`ollama`), `supportedProtocols()` the override shapes it has code for — naming the
default explicitly is a no-op. A provider whose shape is genuinely fixed (`ollama`)
overrides with an empty set, so `/model add --protocol=` rejects the flag instead
of silently accepting a wire that would not be spoken. On the OpenAI-compatible
base, ids map straight onto concrete wire classes (`NaruModelProtocolOpenAICompat`,
`NaruModelProtocolAnthropicCompat`, `NaruModelProtocolGeminiNative`) — there is no
registry of wire types to extend, writing a new wire means writing its class and
building it in `createDefaultProtocol`/`createProtocol`.

Because every configuration lookup in the wire layer is already keyed by the
provider's *name* (`<name>.apiKey`, `<name>.url`, `<name>.timeout`), an instance
created by `newInstance("personal")` is automatically scoped to `personal` — no
protocol or serializer changes are needed to support N instances with N keys.

Type-scoped behaviour (`--provider=` filtering, ollama `pull`/`rm`/`ps`/`unload`,
probe caching) reads `type()` instead of `name()`.

## 12. Recap

1. `/model add <id> --provider=<type> [--apiKey=$VAR] [--model=…] [--params…]`
   registers a named instance of a provider — repeat it to register the same
   provider with different keys.
2. Models are addressed `<id>/<model>`; `/model list` shows everything selectable,
   `/model registered` shows what you registered (keys masked).
3. Config resolves: registration value (`$VAR` interpolated) → `<id>.*` env key →
   provider default (`GEMINI_*` env var), so a registration can pick its key up
   from the environment on its own.
4. Wire types (`openai`, `anthropic`, `gemini`, selected with `--protocol=`) are
   the *only* way to point at a custom HTTP endpoint — `/model endpoint` and
   `custom.endpoints.*` are gone, as are aliases (recreate both as registrations);
   ollama lifecycle commands live in `/ollama`.
