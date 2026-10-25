# Conversation UX: collapsible items, tail control, live activity

Status: implemented in `app/naru-desktop` (this note is the map so the next change does not
have to re-read the whole transcript stack).

Scope: the desktop Conversation/Logs transcript only. No engine, store, scheduler, or terminal
changes.

## Code map

| Concern | File |
| --- | --- |
| Scrollable card column, windowing, filters, tail follow | `ui/TranscriptPane.java` |
| One chat row, long-body fold | `ui/ChatMessageCard.java` |
| HTML body creation/measurement, fold helpers | `ui/HtmlPanes.java` |
| Engine/tool/thinking rows | `ui/TranscriptPane.java#engineCard` |
| Block state (`collapsed`, `expanded`, `previewReturn`, timing, markdown cache) | `model/TranscriptBlock.java` |
| Default audience/lifecycle/fold per `NaruLogMode` | `model/BlockStyle.java` |
| Batches, turn lifecycle, task poll, busy state | `model/NaruSessionModel.java` |
| Live activity counters | `model/TurnActivity.java` |
| Live activity strip | `ui/TurnActivityBar.java` |
| Compact working-tree change counts for the strip | `diff/GitChangeSummary.java` |
| Floating "jump to latest" button | `ui/TailOverlay.java` |

## Invariants that must not be broken

1. **Card virtualisation stays.** `TranscriptPane` renders only a 40-row window; cards are cached in
   `cardCache`. Do not replace it with one component per stored line.
2. **Streaming stays in place.** `cachedCard` must keep re-texting an open engine row rather than
   rebuilding it; chat rows are rebuilt (they own parts/forms).
3. **The stick decision is taken before new rows are added.** `followTail()` and
   `userScrolledAway` are what keep a reader who scrolled up from being yanked down. The tail button
   only reads that state; it must not change it on its own.
4. **HTML measurement is expensive.** Swing measures every logical line of a closed HTML body on
   layout. A closed body therefore starts folded; the fold head is bounded by
   `HtmlPanes.FOLD_AFTER_LINES` (40 lines). A folded body is never the whole 4 000-line dump.
5. **EDT confinement.** Models (`TranscriptModel`, `TurnActivity`) are Swing-free and EDT-confined;
   all updates from engine workers are marshalled by `NaruSessionModel`.
6. **No new task polling.** Busy state comes from the existing flush (`streamingOutput`) and the
   existing 2 Hz task poll. The activity strip owns a 120 ms animation timer only while it is
   visible, plus a bounded `git --numstat` read on a worker every ~3 s while work is running; the
   read never blocks the EDT and is not a second task poll.
7. **Long expanded bodies get their own vertical scroll**, capped at `HtmlPanes.SCROLL_HEIGHT`
   (300 px), so the outer conversation still scrolls as one column. Engine data bodies
   (`BlockStyle.monospace()`) use the mono caption size; engine prose uses secondary, dropping to
   caption when hosted in its own scroller. A chat body in its scroller drops from body to
   secondary, so the conversation prose stays the largest text on screen.

## Collapse model

`TranscriptBlock` already had two independent flags and they are now both used:

- `collapsed` — the item is folded to a short preview. Default comes from
  `BlockStyle.collapsedByDefault()` (Thinking, Scheduler, Debug, Trace, Raw) and from
  `TranscriptModel.TOOL_CARD_COLLAPSE_CHARS` for long tool cards.
- `expanded` — the user has asked for the whole body (past the 40-line fold).

Engine rows show a disclosure chevron in the row heading plus a `view more` / `view less` footer:

- collapsed + preview hidden → 6-line preview (`HtmlPanes.PREVIEW_LINES`)
- not expanded + more than 40 lines → 40-line head
- expanded → full body; if longer than 24 lines it is hosted in its own scroll pane
- `view less` returns to the preview the item came from (collapsed-by-default modes and long tool
  cards both have one), otherwise to the folded head.

Chat rows keep `ChatMessageCard`'s fold but a body past 40 lines also hosts the unfolded text in
the same capped scroller instead of growing the outer column without bound.

A wheel gesture over a body's scroller is forwarded to the outer column once the body reaches its
end (`HtmlPanes.forwardWheelAtEdges`), so the small scroller does not trap the conversation.

## Tail button

`TailOverlay` is the center component of `TranscriptPane`: a `JLayeredPane` holding the one
`JScrollPane` in its default layer and the floating `↓` button in a popup layer. The button is a
sibling of the scroll pane, not a child of it. That is deliberate: the viewport's optimized scroll
path blits its backing store onto the screen, and a button painted inside the scroll pane is painted
over by that blit until something forces a full repaint (the old implementation only reappeared on a
window resize). A sibling in a higher layer is outside the viewport's clip, so scrolling and card
repaints cannot erase it. Tests reach the scroller through `TranscriptPane.scrollPane()`.

`TranscriptPane.updateTailButton()` sets it visible when `!isAtBottom()`; it is refreshed by the
scrollbar adjustment listener, the wheel listener, row rendering, text refreshes, `followTail()`,
and by `TailOverlay.doLayout()` after a resize. The overlay lays out the scroll pane to its full
bounds and positions the button over the lower-right corner; the button never takes part in the
scroll layout.

`BOTTOM_MARGIN` is 4 px, not a wide dead zone: a single trackpad or wheel notch must reveal the
button, while a couple of pixels of layout rounding still count as "at the end". The 48 px
`STICK_MARGIN` is used only for auto-follow, where it bridges content growth before the deferred
scroll lands; deliberate user scrolling uses the exact bottom margin, so even a one-notch scroll
counts as leaving the tail.

The filter bar's existing `↓` button still means "load the next page of matching messages"; the
floating button means "jump to the newest message". Their tooltips say so.

## Live activity

`TurnActivity` is fed the same `TranscriptEvent` batches as the transcript. It records, per turn:

- current kind: starting / thinking / writing / tool / working / waiting / idle
- current and last tool name, plus the last file seen in its arguments
- tool calls started, failed calls, distinct files seen in arguments, start time

`NaruSessionModel` starts a turn when a user line or directive is actually sent, and exposes:

- `isWorking()` — streaming, a directive running, a READY/RUNNING task, or recent turn activity
  (the 2 s startup grace and an 800 ms window after the last event) while the task poll catches up.
  A pending input request or a foreground/conversation task blocked on an event wins over all of
  these: it returns false so the strip cannot animate while the composer is offering Send.
- `isStarting()` — only the boot worker resolving the workspace; the transient `starting…`
  status text after a submitted line is not boot state and must not pin the strip on
- `isWaitingForInput()` — `pending != null` or a task the poll saw `BLOCKED_ON_INPUT`; it no longer
  depends on `!isWorking()`, because an open model block used to keep the strip spinning beside a
  Send button
- `isWaitingOnEvent()`
- `isActivityVisible()` — any of the above

`TurnActivityBar` sits at the bottom of the Conversation tab (below the scrolling cards). It shows a
rotating-dot spinner, a verb, and a summary (`3 files · +42 −8`). The verb is specific when the
current tool names an action (`Reading notes.txt`, `Running bash`, `Editing Foo.java`); otherwise
it rotates through short synonyms for the current kind (`Thinking`, `Pondering`, `Reasoning`, …)
every ~2.4 s. Dots animate at 120 ms. Waiting states are static (no spinner, no rotating verb).

**Update summary:** the right-hand label is the working tree's file/line diff summary, read by
`GitChangeSummary` with `git diff --numstat` for the index and working tree. Hovering the label
shows each file with its own `+`/`−` counts; binary files are named without line counts. The read is
on a `SwingWorker`: one read when the strip appears, a 3 s poll while work runs, and a 700 ms
settle after a tool-call edge, so the EDT and the transcript are never blocked. If git is
unavailable, the directory is not a repository, or there is nothing to report, the label falls
back to `TurnActivity`'s internal counters (`3 tools · 2 files seen · 42s`). The full unified diff
and the Workbench Changes tab are unchanged; this is a compact mirror, not a replacement.

## Things not worth rereading

- **The old fold threshold (120 lines).** It only delayed the expensive layout pass; 40 lines is
  the current bound, and the constant is the single source of truth.
- **A second `JScrollPane` around the whole transcript.** The column still has exactly one
  scrolling viewport; per-card scrollers live inside card bodies and never re-parent the column.
  The floating button is not a component of that viewport, so the viewport can blit without
  erasing it.
- **Putting the floating button back inside the scroll pane.** A child above the viewport is still
  inside the viewport's paint/blit clip; it looks correct after a full repaint and vanishes after
  scrolling or a hover repaint. Keep it in `TailOverlay`'s popup layer.
- **The terminal renderer / ANSI path.** Nothing here parses ANSI or shares terminal state; the
  desktop receives typed `TranscriptEvent`s only.
- **Putting git counts inside `TurnActivity`.** Keep the event projection Swing-free and
  engine-only; `GitChangeSummary` is a separate, bounded read. A failed git call must only hide the
  mini summary, not change turn state or the transcript.
- **Task polling as an activity clock.** The strip animates off its own 120 ms timer and reads the
  existing 2 Hz task snapshot; adding a second task poll would duplicate `NaruSessionModel`'s job.
  The git summary poll is a separate, short-lived file-system read, not a task poll.
