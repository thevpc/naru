# Config-driven custom models

> **When to use this:** you want to point NARU at an HTTP LLM endpoint that has
> no built-in provider class — an OpenAI-compatible server (LM Studio, vLLM,
> llama.cpp, LiteLLM proxy, an OpenAI-compatible cloud) or an Anthropic
> Messages API. Everything is configured through environment keys or the
> `/model endpoint` directive; **no Java class** is required.

NARU ships with built-in provider classes (ollama, openrouter, groq,
cerebras, colibri, ...). For everything else there is the config-driven
provider named `custom`, backed by the key group `custom.endpoints.*`.

## 1. How it works

- One or more named **endpoints** are declared via `custom.endpoints`
  (comma-separated names).
- Each endpoint carries its own env keys (`custom.endpoints.<name>.*`).
- Each endpoint selects a **wire protocol** with `custom.endpoints.<name>.type`:
  - `openapi` (default) — OpenAI-compatible `POST {base}/chat/completions`
  - `anthropic` — Anthropic Messages `POST {base}/v1/messages`
- Models are addressed as `custom/<endpoint>/<model>`.
  A bare `custom/<model>` falls back to the implicit endpoint named `default`.

## 2. Declaring endpoints

### 2.1 With the `/model endpoint` directive (recommended)

```text
/model endpoint add my-llm --url=http://localhost:1234/v1 --models=qwen2.5-coder:7b,llama3.1
/model endpoint add claude --url=https://api.anthropic.com --type=anthropic --models=claude-sonnet-4
/model endpoint list
/model endpoint remove claude
```

`add` accepts:

| Option            | Meaning                                                            | Example                        |
|-------------------|--------------------------------------------------------------------|--------------------------------|
| `--url`           | base URL of the server (required)                                   | `--url=http://localhost:1234`  |
| `--type`          | wire protocol: `openapi` (default) or `anthropic`                   | `--type=anthropic`             |
| `--apiKey`        | API key (stored **private**, never shown in listings)               | `--apiKey=sk-...`              |
| `--models`        | comma-separated model ids exposed by this endpoint                  | `--models=a,b`                 |
| `--chatPath`      | relative chat path (default: `chat/completions` / `v1/messages`)   | `--chatPath=v1/chat/completions` |
| `--contextLength` | max context size in tokens                                          | `--contextLength=32768`        |
| `--tools`         | whether the endpoint natively supports tool calling (`true` default) | `--tools=false`               |
| `--probe`         | reachability probe (`true` default)                                 | `--probe=false`                |

Adding an existing name **updates** its keys. `remove` deletes the endpoint
and all of its keys.

### 2.2 Directly via environment keys

Equivalent to the directive above; keys are identical to the CLI options:

```text
custom.endpoints=my-llm,claude
custom.endpoints.my-llm.url=http://localhost:1234/v1
custom.endpoints.my-llm.models=qwen2.5-coder:7b,llama3.1
custom.endpoints.claude.url=https://api.anthropic.com
custom.endpoints.claude.type=anthropic
custom.endpoints.claude.models=claude-sonnet-4
custom.endpoints.claude.apiKey=sk-...        (private)
custom.endpoints.my-llm.probe=false          (disable liveness probing)
```

Both forms write/read the same keys, so you can mix them.

## 3. Wire protocols

### OpenAI-compatible (`type=openapi`)

Default. Targets any server implementing the OpenAI Chat Completions shape
(`POST {base}/chat/completions`). Send `Authorization: Bearer <apiKey>`
when a key is configured:

```text
/model endpoint add litserver --url=http://localhost:1234/v1 --models=qwen2.5-coder:7b
/model use litserver/qwen2.5-coder:7b
```

Note: when using the implicit `default` endpoint, the address shortens to
`custom/<model>`.

### Anthropic Messages (`type=anthropic`)

Targets `POST {base}/v1/messages` with `x-api-key` + `anthropic-version`
headers and the Anthropic Messages body shape (`system` hoisted to top
level, required `max_tokens`, `tool_use`/`tool_result` content blocks):

```text
/model endpoint add claude --url=https://api.anthropic.com --type=anthropic --models=claude-sonnet-4 --apiKey=sk-...
/model use claude/claude-sonnet-4
```

Unknown `type` values fall back silently to `openapi`.

## 4. Availability and `/models` filtering

- Each endpoint is **probed** by default: NARU issues a short-timeout
  `GET` on the base URL (3 s connect/read, 5 s TTL cache) and treats any
  HTTP response as "reachable". Set `probe=false` to skip the probe and
  always treat the endpoint as usable.
- `custom.isAvailable` is `true` when at least one endpoint is usable
  (or when no endpoints are configured).
- `/model list` / `/models` only shows models from **available** providers:
  an unreachable server disappears from listings automatically.
- You can filter listings by name or part of it:

```text
/models qwen        # models whose name/provider contains "qwen"
/model my-llm/      # provider-scoped, filter-style listing
```

- Explicit addressing still resolves even when a provider is down:
  `/model use colibri/glm-5.2-colibri` keeps working through the static
  fallback while the live server is unreachable.

## 5. Timeouts & retries (both protocols)

Per-endpoint overrides (applied to every request):

| Key                                   | Default |
|---------------------------------------|---------|
| `<prefix>.connectTimeout`             | `120s`  |
| `<prefix>.readTimeout`                | `120s`  |
| `<prefix>.timeout`                    | (alias for both timeouts) |
| `<prefix>.maxRetries`                 | `5` (fallback `model.maxRetries`) |
| `<prefix>.retryPeriod`                | `2s` exponential backoff (fallback `model.retryPeriod`) |

where `<prefix>` is `custom.endpoints.<name>` (or `model` for global
defaults).

## 6. Recap

1. Pick a name, an URL, a protocol type (`openapi` or `anthropic`).
2. Register it: `/model endpoint add <name> --url=<url> [--type=...] [--models=...] --apiKey=...`
3. `custom` endpoints appear in `/models` once reachable; filter with `/models <keyword>`.
4. Switch with `/model use <name>/<model>` (or `custom/<model>` via the `default` endpoint).