# NARU User Guide

> Hands-on documentation for configuring and driving NARU.
> This folder is populated **incrementally** — each guide is added as its
> feature stabilizes, always in Markdown.

## Table of contents

| Guide                                            | Status   |
|--------------------------------------------------|----------|
| [Config-driven custom models](models/config-driven-custom-providers.md) | ✅ |
| More guides (sessions, routines, tools, ...)     | 🚧 coming |

---

## How this guide is organized

- Each major topic lives in its own Markdown file (or sub-folder).
- Every file starts with a short "when to use this" paragraph, then the
  configuration keys, then concrete examples.
- Commands are shown as they would be typed in the NARU REPL
  (`nuts -y naru`), with the leading `/`.

## Quick links

- [Configure an OpenAI-compatible endpoint by config](models/config-driven-custom-providers.md)
- [Configure an Anthropic endpoint by config](models/config-driven-custom-providers.md#anthropic-messages-typeanthropic)
- [Availability probing & `/models` filtering](models/config-driven-custom-providers.md#4-availability-and-models-filtering)