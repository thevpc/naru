# Sessions and tasks

> **When to use this:** you are embedding NARU in your own program — a service, a test
> harness, a CI job, a bot — and you need to run a script, wait for it, and read what it
> produced, without a human at a terminal. This is the whole lifecycle: create a session,
> run a task, wait, read the result.
>
> If you are driving NARU by hand at a prompt, you want [Planning](planning.md) instead.

Everything below is in `naru-api` and needs no extension jars.

---

## Table of contents

- [1. The shape of it](#1-the-shape-of-it)
- [2. Quick start](#2-quick-start)
- [3. Getting an agent](#3-getting-an-agent)
- [4. Sessions: one task or many](#4-sessions-one-task-or-many)
- [5. Waiting for a task](#5-waiting-for-a-task)
- [6. Reading the result](#6-reading-the-result)
- [7. Value, exit code and error are independent](#7-value-exit-code-and-error-are-independent)
- [8. Cancelling, and stopping a session](#8-cancelling-and-stopping-a-session)
- [9. Running without a terminal](#9-running-without-a-terminal)
- [10. A finished task is frozen](#10-a-finished-task-is-frozen)
- [11. What the core can run](#11-what-the-core-can-run)
- [12. Caveats worth knowing](#12-caveats-worth-knowing)
- [13. Troubleshooting](#13-troubleshooting)

---

## 1. The shape of it

Three objects, each with one job:

| Object         | What it is                                                        |
|----------------|-------------------------------------------------------------------|
| `NaruAgent`    | The engine. Holds your project directory and your configuration.  |
| `NaruSession`  | One conversation with the engine. Owns a scheduler and a worker pool. |
| `NaruTask`     | One run of a script. **Its own handle on its own result.**         |

The part worth internalising: **a task is its own result.** There is no separate result
object to look up, and no id to keep. A task deregisters from the session the moment it
finishes, so the object you were handed is the only remaining record of what happened —
which is exactly why the result lives on the task.

```
NaruAgent
  └── NaruSession          ← a scheduler, a worker pool, a stop button
        └── NaruTask       ← a handle: wait on it, then read it
```

## 2. Quick start

```java
NaruAgent agent = new NaruAgentImpl();
agent.projectDirectory(NPath.of("/path/to/project"));

NaruTask task = agent.newSession()
        .task(NaruTaskSpec.of().statements("/return 1+1"))
        .build()
        .run();               // started, and handed back

task.await();                // block until it finishes

System.out.println(task.value());      // 2
System.out.println(task.isSuccess());  // true
System.out.println(task.exitCode());   // 0
```

`run()` returns immediately. Nothing has finished yet — `await()` is what blocks.

## 3. Getting an agent

```java
NaruAgent agent = new NaruAgentImpl();
agent.projectDirectory(NPath.of("/path/to/project"));
```

An agent is cheap to keep around and expensive to keep re-creating: it owns your directive
and tool filters, and the sessions you create from it. Create one per application, not one
per task.

Useful knobs, all optional:

```java
agent.projectDirectory(NPath.of(dir));   // where scripts read and write
agent.directiveFilter(d -> ...);         // hide directives from scripts
agent.toolFilter(t -> ...);              // hide tools from scripts
agent.env().put("API_KEY", NElement.ofString(key), NAruVisibility.PRIVATE);
```

`NAruVisibility` decides who sees the value: `PRIVATE` keeps it to this session's own
scripts, `PUBLIC` exposes it to subagents, `MIXED` for values that are fine either way.

## 4. Sessions: one task or many

**One task per session** is the common case. Configure the task, then `run()` starts both
the session and the task:

```java
NaruTask task = agent.newSession()
        .statements("/return 6*7")     // shorthand for .task(spec)
        .build()
        .run();
```

**Many tasks in one batch** is for a service handling a group of work at once. Start the
session once, then run them all before any of them finishes:

```java
NaruSession session = agent.newSession().build();
session.start();                          // no task configured: it stays up

NaruTask a = session.run(NaruTaskSpec.of().statements("/return 10"));
NaruTask b = session.run(NaruTaskSpec.of().statements("/return 20"));

CompletableFuture.allOf(a.toFuture(), b.toFuture()).join();
System.out.println(a.value().get() + b.value().get());   // 30
```

> **A session stops itself when its last task ends.** Deliberate — a session with nothing
> left to do should not keep a thread pool alive — but it decides the shape of your service.
> Once `a` and `b` above are both done, that session is gone and `session.run(...)` will
> throw `session is not running`.
>
> For request-per-request work, **create a session per request**. It is the pattern that
> cannot surprise you, and it costs one scheduler you never have to reason about:
>
> ```java
> NaruTask task = agent.newSession()
>         .statements(script)
>         .build()
>         .run();
> task.await();
> ```
>
> If you would rather keep one session, you must keep something alive in it: a task that
> never terminates, such as an `interactive` task parked on a question your
> `NaruInteraction` never answers. That works, but it is a trick built on a task refusing
> to finish — reach for it only when you have measured that per-request sessions cost you
> something.

Seed a task's variables from Java rather than letting the script assign them:

```java
NaruTask task = agent.newSession()
        .task(NaruTaskSpec.of()
                .statements("/return base * 2")
                .vars(Map.of("base", 21L)))     // the script sees base
        .build()
        .run();
task.await();
System.out.println(task.value().get());             // 42
```

## 5. Waiting for a task

Four ways, depending on what your program is doing. All of them agree about when the task
is finished.

**Block** — the task is the next thing you want.

```java
task.await();
```

`await()` returns `void`: the task is your handle, so there is nothing to hand back. It
throws `IllegalStateException` on an interactive task, which finishes only when a host
answers it — blocking forever is almost never what you meant there. Use the timed form.

**Block with a deadline** — your program has other work, or the task may never finish.

```java
if (!task.await(Duration.ofSeconds(30))) {
    task.cancel("took too long");
}
```

The `boolean` distinguishes *timed out* from *finished and produced nothing*, which is the
distinction that matters when you decide whether to retry.

**Compose** — you would rather not block a thread.

```java
CompletableFuture<Integer> answer = task.toFuture()
        .thenApply(t -> NLiteral.ofInt(t.value().orNull()).get());
```

Note the `orNull()`: `value()` hands back an `NOptional`, and `NLiteral.ofInt` will not
unwrapping it for you — passing the optional straight in gets you
`NErrorOptionalException: invalid Int Optional@...`, because it tries to parse the optional
itself. Convert first, then parse.

The future completes **normally even when the task failed**. A failure is a result, not an
exceptional condition, and reporting it as one would force every caller to unwrap. Test
`isSuccess()` on the task, or call `throwIfFailed()`.

**React** — fire and forget, with a callback.

```java
task.onComplete(t -> log.info("finished: {} = {}", t.status(), t.value().orNull()));
```

Callbacks run on a shared background executor, never on the scheduler thread that ran the
task, so a slow callback cannot stall the session. Registering on an already-finished task
runs the callback immediately, and one that throws is contained — it cannot stop anyone
else from hearing back.

## 6. Reading the result

All of these are safe to call at any time, and keep working after the task is gone.

| Call                        | What you get                                          |
|-----------------------------|-------------------------------------------------------|
| `value()`                   | what the script produced, else its last result        |
| `value().isPresent()`       | whether there is a value at all                        |
| `exitCode()`                | `0` on success, `1` on failure, `130` when killed     |
| `error()`                   | why it failed, else empty                             |
| `isSuccess()`               | completed, no error, exit code zero                   |
| `isCompleted()`             | whether it has finished, which is then final          |
| `status()`                  | the current `NaruTaskStatus`                           |
| `vars()`                    | the task's own variables, as a `Map`                   |
| `var(k)` / `varOrDefault(k, d)` | one variable, or a fallback                        |
| `endTime()` / `duration()`  | when it finished, and for how long                     |

Anything that could be absent comes back as an `NOptional`, so a missing value and a value of
nothing are told apart instead of both being `null`.

```java
task.await();
System.out.println(task.value());                   // 9
System.out.println(task.var("seed"));                // Optional@...=5
System.out.println(task.var("nope").isEmpty());      // true  -- absent
System.out.println(task.endTime().isPresent());      // true
```

`var()` distinguishes *absent* from *present and set to nothing* — an empty optional versus
a present optional holding null — because a script can set a variable to nothing on
purpose. `varOrDefault` is the shorthand when you do not care which.

`value()` prefers an explicit `/return` and falls back to the last thing the script
computed, so a script that simply ends still reports what it last calculated.

> **`value()` is an `Object`, and a number is not necessarily an `Integer`.** NARU counts in
> `Long` and `Double` interchangeably, so `/return 21*2` hands you a `Long`, and comparing it
> to a boxed `Integer` is how `equals` surprises you:
>
> ```java
> Object v = task.value().orNull();                  // Long(42)
> v.equals(42);                                       // false -- Long(42) != Integer(42)
> ((Number) v).intValue() == 42;                      // true
> ```
>
> Cast to `Number` rather than comparing boxed values, and use `NLiteral.ofLong(v.orNull())`
> when you want a number back in the type you expected.

## 7. Value, exit code and error are independent

These are three separate channels, and a task can report an answer **and** an objection:

```java
if (task.value().isPresent()) {
    consume(task.value().get());
}
if (!task.isSuccess()) {
    report(task.error().orNull(), task.exitCode());
}
```

A caller that checks `isSuccess()` first and throws away the value throws away the partial
answer — usually the thing you actually wanted.

`isSuccess()` means: completed, with no error, and a zero exit code. To treat a failure as
an exception instead:

```java
task.throwIfFailed();   // throws IllegalStateException, or returns the task for chaining
```

## 8. Cancelling, and stopping a session

```java
task.cancel();                    // reason is optional
task.cancel("user navigated away");
```

Cancelling reports the task `KILLED` with exit code `130`, and publishes your reason as
`error()`. It is cooperative — a task that never yields is not forced — idempotent, and
harmless on a task that already finished.

Stopping the session cancels everything still running, so nothing stays blocked on a
question the host is no longer there to answer:

```java
session.stop();
```

You rarely need to: a session stops itself when its last task ends, and `stop()` is
idempotent.

## 9. Running without a terminal

By default a session assumes a human at a console. A server, a test, or a bot should say
how it wants to be talked to instead, by implementing `NaruInteraction`:

```java
class CapturingInteraction implements NaruInteraction {
    final List<String> lines = new CopyOnWriteArrayList<>();

    @Override public String name() { return NAME_STREAM; }
    @Override public void open(NaruSession session) { }
    @Override public void requestInput(NaruInputRequest request) { /* never answer */ }
    @Override public void write(NaruLogMode mode, NMsg message) { lines.add(mode + " " + message); }
    @Override public void close() { }
}

NaruTask task = agent.newSession()
        .interaction(new CapturingInteraction())
        .task(NaruTaskSpec.of().statements("/return 6*7"))
        .build()
        .run();
```

Two rules the engine relies on, both of which follow from it never assuming a terminal:

- `write` and `requestInput` are called **from worker threads**, possibly several at once,
  and **must not block**.
- The answer to a question arrives later through `NaruInputRequest.deliver(String)`, not as
  a return value. Returning nothing parks the task on the question; it does not fail it.

`requestInput` on a session with **no** listener is different: the engine treats it as a
question nobody will ever answer and fails the task with a reason, rather than leaking a
task that is blocked forever.

## 10. A finished task is frozen

Once a task reaches a completed state its **outcome stops changing forever**: `endTime()`,
`error()`, `duration()`, `value()`, `exitCode()` and `vars()` are all
snapshotted at that moment. Writing to a finished task's variables does not rewrite its
recorded results.

This is not fussiness. A task is deregistered as soon as it ends, so the object in your
hand may be the only remaining record of what happened — and a record that a later write
can edit is not a record.

The *descriptive* setters (`projectDir`, `taskMode`, `promptMode`, …) stay live, because
those describe the task rather than its run. So `NaruTask` is not a value type: do not
cache one expecting immutability. Ask it again when you need to know.

## 11. What the core can run

The core jar ships these directives: `/exit`, `/print`, `/help`, `/buffer`, `/assert`,
`/goto`. And these statement keywords, which are parsed by the engine rather than looked
up as directives:

`/return` · `/if` · `/else` · `/elseif` · `/end` · `/for` · `/while` · `/goto` · `:` (label)

`/return` is worth singling out, because it is how a script tells you what it produced:

```
/return 6*7
```

Anything else — `/set`, `/ask`, `/plan`, tool calls, model calls — lives in an extension
jar. Add it to the classpath and it appears; remove it and the feature is gone, with
nothing in `naru-api` or `naru-impl` referring to it. See
[Planning](planning.md) for `naru-tools-plan`.

An unknown directive is rejected **when the task is built**, before anything runs, so a
typo fails immediately instead of half way through.

## 12. Caveats worth knowing

**A statement error is logged, not failed.** A script with a bad expression prints an error
and can still finish `DONE`, with a value and `isSuccess() == true`:

```java
// "/return 5", "/print 1+"  -- the second statement is nonsense
task.status();      // DONE
task.error();       // null
task.isSuccess();   // true
```

So `isSuccess()` means "the task ran to the end", not "nothing went wrong". Watch your
output stream, or check `vars()`, if you need to know a script misbehaved. The one case
that *does* produce `FAILED` is an input request the engine gives up on.

**A nonzero exit code with a value needs an extension.** Publishing `lastExitCode` is done
by `naru-tools-routines` (`/set` and friends), not the core. In the core, a task is `DONE`
with exit code `0`, or `FAILED` with `1`, or `KILLED` with `130`.

**`/help` does not list `/return`.** Statement keywords are not registered directives, so
they are absent from the directive list and from the "unknown directive" error message.

**`value()` is an `Object`.** See the note in [§6](#6-reading-the-result) — a numeric
result may arrive as a `Long`, so cast to `Number` instead of comparing boxed values.

**`await()` on an interactive task throws.** Use `await(Duration)`, or answer the question.

**A statement error is logged, not failed.** See [§12](#12-caveats-worth-knowing).

## 13. Troubleshooting

**`IllegalStateException: session is not running`** — the session already stopped, which it
does on its own when its last task ends. Either create a new session, or keep a long-lived
task on it. See [§4](#4-sessions-one-task-or-many).

**`IllegalStateException: run() was already called on this session`** — the configured task
is one specific invocation. Use `session.run(spec)` for further work.

**`NEmptyOptionalException`** — you called `get()` on an empty `NOptional`. A common way in
is reading a variable that does not exist: use `varOrDefault(k, d)`, or check
`var(k).isEmpty()` first.

**The task finished but `value()` is empty** — the script never `/return`ed and computed no
result. Check `value().isPresent()` before reading, and `vars()` for what it did set.

**`NEmptyOptionalException: missing directive : foo`** — the directive is not on the
classpath. It is an extension feature, or a typo.
