# NARU User Guide

> Hands-on documentation for configuring and driving NARU.
> This folder is populated **incrementally** — each guide is added as its
> feature stabilizes, always in Markdown.

## Table of contents

| Guide                                            | Status   |
|--------------------------------------------------|----------|
| [Sessions and tasks](content/sessions.md)                | ✅ |
| [Config-driven custom models](content/config-driven-custom-providers.md) | ✅ |
| [Planning](content/planning.md)                           | ✅ |
| [Context compaction](content/compaction.md)             | ✅ |
| More guides (routines, tools, ...)                | 🚧 coming |

---

## How this guide is organized

- Each major topic lives in its own Markdown file (or sub-folder).
- Every file starts with a short "when to use this" paragraph, then the
  configuration keys, then concrete examples.
- Commands are shown as they would be typed in the NARU REPL
  (`nuts -y naru`), with the leading `/`.

## Quick links

- [Run a script from Java and read the result](content/sessions.md)
- [Waiting: block, time out, compose, or react](content/sessions.md#5-waiting-for-a-task)
- [Running NARU without a terminal](content/sessions.md#9-running-without-a-terminal)
- [Configure an OpenAI-compatible endpoint by config](content/config-driven-custom-providers.md)
- [Configure an Anthropic endpoint by config](content/config-driven-custom-providers.md#anthropic-messages-typeanthropic)
- [Availability probing & `/models` filtering](content/config-driven-custom-providers.md#4-availability-and-models-filtering)
- [Plan a goal as a dependency graph, and activate it](content/planning.md)
- [`/plan` directive reference](content/planning.md#4-directives)
- [Planning tools the model can call](content/planning.md#5-tools)
- [Summarize an old conversation without losing it](content/compaction.md)
- [`/compact` directive reference](content/compaction.md#7-directives)
