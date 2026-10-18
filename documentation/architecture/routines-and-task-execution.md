# Routine execution and task inputs

This note describes how a routine runs inside an existing task, how `/start` runs it as a new task, and how named inputs reach each execution mode.

## Two execution scopes

Naru has two routine execution paths:

| Invocation | Execution scope | Scheduling | Input scope |
|---|---|---|---|
| `/call <routine> name=value` | A new frame on the current task | Synchronous; execution returns to the caller when the routine frame finishes | Frame parameters |
| `/start <routine> name=value` | A newly created child task | Starts the child in the background; the caller can continue | Child task environment |

`/source <routine>` is a third option: it inlines the routine into the current frame rather than creating a separate frame or task.

## `/call`: routine frame in the current task

`/call` is parsed as a script statement, not dispatched through the directive registry. The task parses the routine name and named arguments, pushes a frame for that routine, and binds the arguments to that frame before it executes. When the frame is exhausted, the task pops it and continues the caller's frame.

A routine frame owns its parameters and local variables. Parameter values are resolved before local variables and task environment values. Parameters are treated as inputs: assignment to a parameter name does not replace the argument. `/call` accepts named `name=value` arguments; positional arguments are rejected.

The call shares the task's execution context, history, task environment, working directory, and model settings. It does not create a new task or establish a separate security boundary. It also does not inherit the caller frame's local variables by default.

## `/start`: routine in a new task

`/start` resolves its target and builds a `NaruTaskSpec` with the resolved routine statements, parent task ID, and spawn policy inputs. The session resolves the spawn plan, constructs a distinct task, seeds it, and schedules it to run. `/start` returns after creating the child; use task-waiting facilities such as `/wait --for=children` when the parent needs to join with child completion.

Named inputs supplied on `/start` are copied into the new task environment before the task's statements, initialization hooks, or system prompt run. Routine expressions and prompt templates can therefore read them by name. They are ordinary task variables and can be changed by the child. The child task does not share the parent's task environment map.

Spawn context and permissions are resolved through `NaruSpawnPlanner` and session extensions. Options such as `--fork`, `--window`, `--summary`, `--inherit`, `--add-tags`, and `--policy` affect the child context and grants; they do not change the parameter binding model.

## Parameter model

Inputs are named rather than positional. For script invocations, write `name=value`; quote a value containing spaces, for example `topic="cache design"`. This preserves the meaning of a call if new inputs are added or reordered. There is currently no routine signature declaration, required-input validation, type schema, or default-value syntax: routines read the names they need, and unspecified names resolve through the normal variable scopes.

At the API level, `NaruTaskSpec.param(name, value)` and `NaruTaskSpec.params(map)` provide typed named inputs. They are seeded into the task environment and override same-named values supplied through `vars(...)`. The older `vars(...)` API remains available for general task environment seeding.

## Implementation path

- `NaruStatementHelper` recognizes `call` as a statement keyword and constructs `NaruCallStmt`.
- `NaruTaskImpl.call` validates named arguments, pushes the routine frame, and binds its frame parameters.
- `NaruStartDirective` parses target names, named inputs, and spawn flags into a `NaruTaskSpec`.
- `NaruSessionImpl.newTask` seeds `NaruTaskSpec.params()` (after `vars()`) before task execution begins.
- `NaruTaskImpl.resolveVariable` resolves frame parameters, local variables, inherited frame variables when enabled, and then task/session environment values.
