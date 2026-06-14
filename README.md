Save it. Run it. Let the agent rewrite it at runtime if it needs to.

```bash
/routine save bug-fix
/routine run
```

Spawn parallel tasks that communicate via typed events:

```bash
/start review-security
/start review-performance
/task await review-security
/task await review-performance
```

One task blocked on a 3-minute LLM call never blocks the others.

---

## How it compares

|                       | LangChain4j | LangGraph  | AutoGen  | Temporal | NARU |
|-----------------------|-------------|------------|----------|----------|------|
| **JVM-native**        | ✅           | ❌          | ❌        | ✅        | ✅    |
| **Durable**           | ❌           | Partial    | ❌        | ✅        | ✅    |
| **LLM-aware**         | ✅           | ✅          | ✅        | ❌        | ✅    |
| **Actor/event model** | ❌           | ❌          | Partial  | ❌        | ✅    |
| **Self-modifying**    | ❌           | ❌          | ❌        | ❌        | ✅    |
| **REPL-is-script**    | ❌           | ❌          | ❌        | ❌        | ✅    |

Temporal has durability but no LLM awareness.
LangChain4j has LLM awareness but no durability.
LangGraph has both but is Python-only with a static graph.
NARU is the only JVM runtime that is durable, LLM-native, and dynamic.

---

→ [See the full help](HELP.md) for more examples including databases, IDEs, security tools, and games.
→ [See Examples](examples/README.md) for more examples including databases, IDEs, security tools, and games.

---

## Roadmap

- [ ] Cloud LLM SPIs (Claude, OpenAI, Gemini)
- [ ] Streaming output
- [ ] Embedding SPI + pgvector
- [ ] RAG pipeline
- [ ] MCP CLI

---

## Citation

```bibtex
@software{bensalah2026naru,
  author       = {Ben Salah, Taha},
  title        = {NARU: A Durable Actor-Based AI Agent Runtime for the JVM},
  year         = {2026},
  publisher    = {GitHub},
  howpublished = {\url{https://github.com/thevpc/naru}}
}
```