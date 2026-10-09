# NARU User Guide

> Hands-on documentation for configuring and driving NARU.
> This folder is populated **incrementally** — each guide is added as its
> feature stabilizes, always in Markdown.

## Table of contents

| Guide                                            | Status   |
|--------------------------------------------------|----------|
| [Sessions and tasks](content/sessions.md)                | ✅ |
| [Projects, navigation and hooks](content/projects-and-hooks.md) | ✅ |
| [Model registrations](content/model-registrations.md)    | ✅ |
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
- [`/cd` vs `/project`, and the init hooks they trigger](content/projects-and-hooks.md)
- [Session-wide defaults and the deprecated init-on-cd flag](content/projects-and-hooks.md#5-the-deprecated-init-on-cd-behaviour)
- [Register the same provider with several API keys](content/model-registrations.md#2-quick-start-two-keys-for-one-provider)
- [Available vs registered models](content/model-registrations.md#1-available-vs-registered)
- [`/model add` reference](content/model-registrations.md#4-the-model-directive)
- [Configure an OpenAI-compatible or Anthropic endpoint](content/model-registrations.md#8-wire-types-openai-anthropic-gemini)
- [Availability probing & `/models` filtering](content/model-registrations.md#9-availability-probing-and-listings)
- [Plan a goal as a dependency graph, and activate it](content/planning.md)
- [`/plan` directive reference](content/planning.md#4-directives)
- [Planning tools the model can call](content/planning.md#5-tools)
- [Summarize an old conversation without losing it](content/compaction.md)
- [`/compact` directive reference](content/compaction.md#7-directives)
