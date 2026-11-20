# Routines and named inputs

Routines are reusable scripts. You can run one in the current task with `/call`, or start it as a separate background task with `/start`. Both forms accept named inputs using `name=value`.

## Run a routine in the current task

```text
/call summarize topic="cache design"
```

`/call` runs synchronously: the current task executes the routine, then continues with the next statement after the routine finishes. Inputs are local to that routine call. Use named arguments; positional arguments are not supported.

For example, a routine can read `$topic` in a prompt or use `topic` in an expression:

```text
/use summarize
10 /print "Summarizing: $topic"
20 /return topic
```

Then call it:

```text
/call summarize topic="cache design"
```

## Start a routine as a new task

```text
/start summarize topic="cache design"
```

`/start` creates and schedules a separate child task. It runs in the background while the current task continues. The named inputs are available to the child as task variables before its statements and prompt run. The child can change its own task variables without changing the parent's environment.

You can wait for children when you need them to finish before continuing:

```text
/start summarize topic="cache design"
/wait --for=children
```

The usual `/start` options can be combined with named inputs, for example:

```text
/start summarize topic="cache design" --window=6turns --inherit=tags
```

## Choosing between `/call` and `/start`

| Use | Choose | What happens |
|---|---|---|
| A helper routine that should finish before the next line runs | `/call` | Adds a routine frame to the current task and returns when it finishes |
| Independent work that can proceed while the current task continues | `/start` | Creates a child task that runs in the background |
| A script that should be inlined into the current frame | `/source` | Inserts the routine into the current execution flow |

`/start` also accepts an agent `.md` target. Named inputs for that target are available as variables in the new task.

## Passing inputs from Java

A host can pass typed values through `NaruTaskSpec`:

```java
NaruTaskSpec spec = NaruTaskSpec.of()
        .statements("/print topic")
        .param("topic", "cache design")
        .param("attempt", 2);
NaruTask task = session.newTask(spec);
```

Use `.params(map)` to pass several values at once. Values supplied with `.param(...)` or `.params(...)` override same-named entries passed with `.vars(...)`.

## Current input behavior

Inputs are named and optional. There is no routine signature declaration yet, so Naru does not validate required names, types, or defaults. An input not supplied by the caller may still resolve from another available variable scope. Script-form values are strings; Java callers can pass typed values.
