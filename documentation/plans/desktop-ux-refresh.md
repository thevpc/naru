# NARU Desktop: UI/UX assessment and refresh plan

Status: **proposal — visual direction awaiting owner decision**
Scope: `app/naru-desktop` only.
Non-goals: changing agent behaviour, the terminal REPL, engine semantics, or the desktop's
feature set. This plan changes how the existing cockpit looks, reads, and feels; it does not
add a new workflow.

---

## 0. Baseline: message reliability (already fixed)

The "message typed but never stored or shown" defect is fixed before the visual work, because a
beautiful screen that loses input is still broken. Evidence from the running build:

- A new session now writes `session.tson` at boot, so `checkpointTask` no longer returns early at
  `!store.exists(uuid(), scope)`.
- Submitting a prompt writes the durable record
  `tasks/1/history/0000000000000001.tson` (`role: "user"`, full content) and appends its id to
  `task.tson.history`.
- Submitting a line repaints the transcript region immediately (verified by screenshot diff), so
  the live echo is visible before the store callback lands.
- Stored copies are de-duplicated against the live echo and against streamed assistant text; the
  non-transitive transcript sort was removed so insertion order is conversation order.
- A rejected line keeps its text in the composer and states the reason inline.

The only turn that failed in the smoke run failed for an account reason
(`HTTP 402 Payment Required` from the provider), not a UI or persistence reason.

Keep these behaviours under regression tests while restyling. Do not reintroduce sorting,
model-level colour decisions, or "clear first, validate later" in the composer.

---

## 1. Method and evidence

Assessment is based on the running desktop (1280×800) plus a read of the UI sources. The model
used for this review cannot view images, so the visual claims below come from code, component
sizes, and programmatic screenshot statistics (dominant colour, changed regions), not from
eyeballing a mock-up.

Files inspected:

| Area | File |
| --- | --- |
| Window, chrome, session rail, welcome | `MainWindow.java` |
| Theme, arcs, font-scale machinery | `NaruDesktop.java` |
| Theme picker and interface size | `ui/SettingsPane.java` |
| Status strip | `ui/StatusBar.java` |
| Message cards and speaker colours | `ui/ChatMessageCard.java` |
| Transcript windowing, search, filters | `ui/TranscriptPane.java` |
| Composer, queued tray, model/mode controls | `ui/InputPane.java` |
| Log-mode styling | `model/BlockStyle.java` |

Measured facts:

- 92 direct `setFont` / `deriveFont` / hard-coded `new Color(...)` / hex-colour call sites in the
  UI package and `MainWindow`.
- 23 emoji glyphs in production UI strings (speakers, buttons, state markers).
- Font sizes in active use: 9 pt (HTML), 11, 11.5, 12, 12.5, 13, 18, 20, 24 — nine sizes for a
  four-level hierarchy.
- Session rows are fixed at 58 px and built as inline HTML; the rail is fixed at 220 px.
- `DEFAULT_THEME` is `FlatLightLaf`, but the captured window's dominant colour was `#46494B`
  (dark grey). The theme is likely overridden by the persisted preference from an earlier run.
  This is exactly the "which theme am I in?" ambiguity the refresh must remove.
- Role and state colours are duplicated: `#10B981`, `#F59E0B`, `#EF4444`, `#6B7280`, `#9CA3AF`
  in the rail; `59,130,246`, `139,92,246`, `245,158,11`, `16,185,129` in cards; a separate set of
  dark greens, oranges, purples, and greys in `BlockStyle`.

---

## 2. Assessment

### 2.1 What already works

- **Information architecture is sound.** Session rail → task/conversation → composer, with an
  inspector and a status strip, is the right cockpit shape.
- **The transcript is virtualised.** Only a window of cards is realised, which is why 50 000 lines
  stay inside the flush budget. The refresh must not replace this with a single text pane.
- **Keyboard paths exist.** `Ctrl+L` focuses the composer, `Ctrl+,` opens settings, `F6` cycles
  panes, `Ctrl+±/0` scales text, `F2` renames a session, and every primary workflow has a menu
  item.
- **Input validity is visible.** The composer explains why it is disabled instead of silently
  dropping a line.
- **Theme and scaling are already persisted** and applied before the frame is shown, which is the
  correct order for Swing.

### 2.2 What reads as unprofessional

**P0 — blocked by visual inconsistency**

1. **No single visual language.** Colours, sizes, and spacing are decided at each call site. The
   same state (open/active/error) is a different colour in the rail, the card, and the status bar.
2. **Emoji as iconography.** `👤 🔧 ✨ 📑 ℹ️ ⚡ 🧠 📎 ➤ 📋 ⚙` render differently per platform and
   font, carry unrelated colour, and make the product look like a chat demo rather than a tool.
3. **The chrome does not match the brand.** The app icon is deep navy with a cyan/blue gradient
   (`#001030` field, `#1090F0 → #20E0F0` mark), but the UI uses default FlatLaf accents and
   generic blue/green/violet role colours.
4. **Nine type sizes with no scale.** 11.5 px status text next to 12.5 px speaker labels next to
   13 px body is noise, not hierarchy.
5. **Inline HTML styling.** Session rows, welcome copy, prompt tips, and card bodies embed
   `color:` and `font-size:` in HTML. This cannot follow the theme and cannot be tested.

**P1 — usability and craft**

6. **Everything competes.** The welcome card, the session rail, the top bar's session pill, the
   right-hand tabs, the task pane, and the status bar all use borders and accent colour at similar
   strength. There is no clear primary surface.
7. **Session rows are dense and cryptic.** A status dot, an HTML name, a status word, and a date
   are packed into 58 px; hibernated, opening, and attention states differ mainly by hue.
8. **Cards are bubbles for every role.** Assistant answers, notices, tool output, and user lines
   all get a rounded, bordered box. Long agent answers should read as document text, not as chat
   bubbles. Tool output should recede until opened.
9. **Empty and loading states are placeholders.** "Session is hibernated" is a centred paragraph;
   there is no skeleton, no suggested next action beyond buttons, and no persistent identity for
   the app while a project loads.
10. **The status bar is a sentence of abbreviations.** `saved`, `Quota —`, `Context 42%`, `2 tasks
    (1 waiting)` are joined by `·` with equal weight. Nothing is prioritised; the number that
    matters during generation (state, context, spend) is not visually distinct.
11. **Custom undecorated title bar without platform affordances.** Drag and double-click work, but
    there is no resize cursor feedback at the edges beyond the invisible border, no snap-layout
    hint, and the window controls are text glyphs (`— □ ×`) rather than platform icons.

**P2 — polish**

12. Scrollbars, focus rings, separators, and hover states are whatever the current Laf supplies.
    Focus is especially weak on the session list and the composer.
13. There is no motion at all: no transition when a session opens, no card entrance, no progress
    shimmer. That is better than gratuitous animation, but a 100–160 ms fade on surface changes
    would remove the "flicker" when panes swap.
14. Copy is inconsistent: "New session…", "＋ New Session", "＋ New", "Create", and "Open session"
    describe the same verbs with different casing and punctuation.

---

## 3. Design direction (decision required)

Three coherent directions. All three keep the same layout; they differ in mood and default.

### Option A — Graphite (dark-first, brand cyan) — **recommended**

A deep navy-graphite cockpit that matches the app icon. Cyan is reserved for the one thing the
user should act on (composer focus, primary button, active session), never for decoration.
Light mode is a first-class sibling, and "Follow system" is the default.

- Canvas `#0B1020`, surface `#111827`, raised `#162033`, border `#243044`.
- Text `#E6EDF7`, muted `#94A3B8`.
- Accent `#38BDF8` (hover `#7DD3FC`, pressed `#0EA5E9`), accent-on `#04121C`.
- Semantic: success `#34D399`, warning `#FBBF24`, danger `#F87171`, info `#60A5FA`.

Why: the icon, the "cockpit" metaphor, and long agent sessions all favour a low-luminance
surface. Cyan on navy is distinctive without being loud.

### Option B — Paper (light-first, ink + cyan)

A calm, document-like workspace for daytime use. The transcript is the page; chrome is quiet.

- Canvas `#F7F9FC`, surface `#FFFFFF`, border `#E2E8F0`.
- Text `#0F172A`, muted `#64748B`.
- Accent `#0284C7`, semantic `#059669 / #D97706 / #DC2626 / #2563EB`.

Why: closest to the current default and least disruptive. Risk: less distinctive, and large dark
blocks of code/tool output dominate the page.

### Option C — Native / follow platform

Use FlatLaf's system-following mode and the host accent, with NARU tokens layered on top for
spacing, type, and state. Lowest maintenance, most "belongs on this desktop".

Why: ideal for enterprise fleets and accessibility settings. Risk: the product has no memorable
identity across platforms, and contrast is harder to guarantee.

**Recommendation:** Option A, with Option B as the light theme and a "Follow system" switch in
Settings. Do not ship a third bespoke palette.

**Decision needed before Phase 1:** (a) A, B, or C; (b) accent cyan `#38BDF8` or a neutral
indigo `#818CF8`; (c) whether the default is Follow system or Dark.

---

## 4. Design tokens

Introduce `ui/theme/UiTheme.java` (or `DesktopTheme`) as the only place that knows colours.
Components ask for semantic roles, never raw hex. Values below assume Option A + B.

### 4.1 Colour roles

| Role | Dark (A) | Light (B) | Used by |
| --- | --- | --- | --- |
| `canvas` | `#0B1020` | `#F7F9FC` | window background |
| `surface` | `#111827` | `#FFFFFF` | rail, panels, cards |
| `surfaceRaised` | `#162033` | `#FFFFFF` | popups, hovered rows |
| `surfaceSunken` | `#080D18` | `#F1F5F9` | composer field, code blocks |
| `border` | `#243044` | `#E2E8F0` | 1 px separators |
| `borderStrong` | `#334155` | `#CBD5E1` | focused inputs, dividers |
| `text` | `#E6EDF7` | `#0F172A` | primary copy |
| `textMuted` | `#94A3B8` | `#64748B` | timestamps, metadata |
| `textFaint` | `#64748B` | `#94A3B8` | disabled, placeholders |
| `accent` | `#38BDF8` | `#0284C7` | primary action, focus, active |
| `accentHover` | `#7DD3FC` | `#0369A1` | hover |
| `accentSoft` | `rgba(56,189,248,.12)` | `rgba(2,132,199,.08)` | selected row, user tint |
| `onAccent` | `#04121C` | `#FFFFFF` | text on accent |
| `success` | `#34D399` | `#059669` | completed, saved |
| `warning` | `#FBBF24` | `#D97706` | waiting, quota near limit |
| `danger` | `#F87171` | `#DC2626` | error, destructive |
| `info` | `#60A5FA` | `#2563EB` | notices |

Rules:

- Accent is used for at most one primary action per surface.
- Semantic colours are for state only, never for speaker identity.
- No component may call `new Color(...)` outside `UiTheme`, `BlockStyle` syntax tokens, and the
  brand mark.
- Theme switches must rebuild existing components, not just the frame; `UiTheme` exposes a
  listener list and the app repaints open panes.

### 4.2 Type scale

Use the system UI face (`SansSerif` / Segoe UI / SF / Inter if bundled) and a mono face
(`JetBrains Mono` if present, else `Monospaced`) for code and tool output.

| Token | Size / line | Weight | Use |
| --- | --- | --- | --- |
| `caption` | 11 / 15 | 500 | timestamps, status metadata, tab counts |
| `label` | 12 / 16 | 600 | speaker names, buttons, field labels |
| `body` | 13 / 20 | 400 | transcript, settings copy |
| `bodyLarge` | 14 / 22 | 400 | welcome paragraph, empty states |
| `title` | 16 / 22 | 700 | pane titles, dialog titles |
| `hero` | 24 / 30 | 700 | welcome headline |

That is six sizes, each with one job. Retire 11.5, 12.5, 18, and 20. Do not ship bold body text
except inline emphasis.

### 4.3 Spacing, radius, elevation

- Spacing scale: `4, 8, 12, 16, 24, 32, 48`. All padding and gaps come from this scale.
- Radii: `6` controls, `8` cards, `12` panels/dialogs, `999` pills. The composer may use `12`.
- Elevation: borders carry structure. One soft shadow only for menus/popups and the composer
  when focused. No drop shadows on transcript cards.
- Borders: `1 px` at `border`; a focused input uses `1 px borderStrong` + a 2 px accent glow
  (`accentSoft`), never a thick outline.
- Content measure: transcript and composer text capped at `920 px`, centred in the available
  width. Code blocks scroll horizontally rather than wrapping.

### 4.4 Icons

Replace emoji with a small, single-weight line-icon set drawn as 16×16 `Icon` implementations
(no new dependency required; if one is added, prefer a 16 px outline set with `currentColor`-like
tinting). Required glyphs: user, assistant/spark, tool/wrench, summary/document, notice/info,
model/chip, thinking/brain, attach/paperclip, send/arrow, copy, check, settings/gear, plus, close,
minimise, maximise, restore, chevron, search, filter, warning, error.

If icon drawing is deferred, use **text labels** (`You`, `NARU`, `Tool · bash`, `Copy`) rather than
emoji. Text is always acceptable; emoji is not.

---

## 5. Layout and chrome

Target grid (1280×800, scale-independent):

```
┌──────────────────────────────────────────────────────────────────────┐
│ title bar 48 px: brand · project/session pill · view tabs · window   │
├───────────────┬──────────────────────────────────────┬───────────────┤
│ session rail  │ transcript                            │ inspector     │
│ 240 px        │ max 920 text measure, virtualised     │ 320 px        │
│ search        │                                       │ (collapsible) │
│ rows 52 px    ├──────────────────────────────────────┤               │
│ task list     │ composer (max 920)                    │               │
├───────────────┴──────────────────────────────────────┴───────────────┤
│ status bar 26 px: state · model · context · spend · saved            │
└──────────────────────────────────────────────────────────────────────┘
```

### 5.1 Title bar and top bar

- Keep the undecorated shell for brand control, but make it behave like a native bar:
  48 px tall, 1 px bottom border, brand mark at the far left, window controls at the far right.
- Replace text glyphs with drawn icons; give minimise/maximise/close 32×28 hit targets and a
  hover background (`surfaceRaised`), close hover `danger`.
- Add resize cursors and a 1 px accent line while dragging.
- Move the menu bar into a compact "hamburger" or keep `File/View/Session/Help`, but do not let
  the menu, the session pill, and the window buttons all compete. The current three-part bar is
  busy. Target: brand + session pill on the left, view switch (Conversation · Logs) as a quiet
  segmented control in the middle, settings and window controls on the right.
- Show the project name, not just "NARU", and use the project as the window subtitle.
- Keep `Ctrl+L`, `Ctrl+,`, `F6`, `F2`, `Ctrl±/0` exactly as they are.

### 5.2 Session rail (240 px)

- Header: `Sessions` in `title`, a single primary `+` icon button with tooltip
  "New session (Ctrl+N)". Remove the duplicate `↗` open button; opening is a row action.
- Search field below the header, full width, with a leading search icon and a clear button.
- Row: 52 px, no HTML. Left column is a 8 px status dot (`success/warning/danger/textFaint`)
  plus the session name in `label`; second line is `state · relative date` in `caption`/muted.
  Selected row uses `accentSoft` with a 2 px accent left rule, not a solid selection fill.
- States must not rely on colour alone: open = filled dot, opening = ring with progress, needs
  attention = warning triangle, hibernated = hollow dot.
- Empty state inside the rail: "No sessions yet" + `New session` link, not blank space.
- Task list below the rail keeps its split but gets the same row rhythm and a clear `Tasks`
  section label; the divider should be a `border` line, not a second header bar.

### 5.3 Transcript

- Background `canvas`; cards become flat blocks separated by 16 px of space, not floating bubbles.
- Each block:
  - speaker row: 16 px icon + name in `label` + optional metadata (model, duration) in `caption`;
  - body: 13/20 `text`, selectable, markdown rendered in the same typographic scale;
  - user blocks: `accentSoft` background with a 2 px accent left rule, no border; label `You`;
  - assistant blocks: no background, no border; label `NARU`; this is the document;
  - notice/system: `warning` or `info` left rule, `surfaceRaised` background;
  - tool calls: collapsed summary row (`Tool · bash`, exit status, duration) with a chevron; output
    in mono at 12/18 when expanded, scrollable after 320 px; never a card with a coloured heading.
- Streaming state: a 2 px accent caret or a 3-dot pulse at the end of the current block; no
  full-width progress bar. "Thinking" content gets a muted, collapsible block, never a coloured
  speaker role.
- Message actions (copy, retry, open details) appear on hover/focus in `textMuted`, 24 px targets.
  They must also be reachable by keyboard.
- Preserve virtualisation, windowing, `maxFlushMs`, and the 50 000-line budget. Restyling must not
  create one component per stored line outside the window.
- Empty transcript: a centred, quiet block with the project name, one sentence, and two actions
  (`Ask NARU`, `Open logs`). No emoji, no marketing paragraph.

### 5.4 Composer

- One rounded `surfaceSunken` panel, max 920 px, 12 px radius, 1 px border; focus adds the accent
  glow. The send button is the only accent-filled control.
- Top line (quiet): model chip (`deepseek-flash`), mode segmented control (Agent/Ask/Plan),
  thinking toggle. Use text/chips, not `⚡/🧠`.
- Text area: 13/20, 3–8 visible lines, no inner border, placeholder
  "Message NARU…  (Ctrl+Enter to send)".
- Bottom line: attach as an icon button with tooltip, queued count as a subtle pill only when
  non-zero, hint text `Enter to send · Shift+Enter for newline` in `caption`.
- Inline validation: red `danger` text below the field, field border turns `danger`; the text is
  never cleared on rejection.
- Queued messages: a collapsed tray above the composer showing `2 queued` and a chevron; each row
  can be removed. Queued lines already echo into the transcript immediately — keep that.

### 5.5 Status bar (26 px)

- Left: session state with a 6 px dot (`Ready`, `Waiting for input`, `Running`, `Error`).
- Middle-left: model name as a subtle chip.
- Right, in priority order: context `42%` with a 48 px micro-bar, spend/quota only when known,
  token counts in `caption`, save state as a quiet check + `Saved`.
- Drop separators where spacing can do the job; keep at most two `·` separators.
- Never show `Quota —` or `Context —`; hide unknown metrics instead of printing an em dash.
- All values update through the existing `NaruSessionModel` listener; no new polling.

### 5.6 Inspector / side panels

- One consistent panel surface with a 40 px header, title in `title`, and a close/collapse
  button. Tabs (`Conversation`, `Logs`, `Tasks`, `Tools`, `Permissions`) use an underline
  indicator in accent, not buttons.
- The inspector collapses to a 40 px rail of icons on narrow windows; `F6` cycles focus.
- Log tables use the same `BlockStyle` semantic roles but map them to theme colours, not the
  current hard-coded dark greens/oranges. Status is text + icon, not only colour.
- Settings becomes a normal dialog/page with sections (Appearance, Sessions, Models, Advanced),
  tokenised spacing, and a visible "Follow system" theme option. Keep the interface-size slider,
  but label it `Text size` with `100%` as the default and a `Reset` action.

---

## 6. Copy and tone

- Sentence case everywhere: `New session`, `Open session`, `Hibernate session`, `Text size`.
- Ellipsis only for actions that open a dialog: `Rename session…`, `Delete session…`.
- Verbs are consistent: **New**, **Open**, **Hibernate**, **Delete**. Do not mix "create",
  "launch", "close", and "hibernate" for the same operation.
- Empty states say what the surface is for and offer one next action.
- Errors state the cause and the next step, in that order: "No model selected. Choose one with
  `/model` or the model chip."
- No exclamation marks, no "Welcome to ✨", no emoji.

---

## 7. Motion and feedback

- 120 ms ease-out on hover/pressed and on panel collapse; 160 ms on dialogue/dialog appearance.
- No animation on transcript load or streaming text beyond the caret/pulse.
- Respect `prefers-reduced-motion` where the platform exposes it; provide a Settings toggle
  `Reduce motion`.
- Hover: background shift only (`surface` → `surfaceRaised`), no scale transforms.
- Focus: 2 px accent ring on the focused control, visible in both themes.
- Loading a project: a skeleton rail (3 rows) + a centred determinate label once a count is known;
  never a modal veil that blocks the window without a cancel path.

---

## 8. Accessibility

- Contrast: body text ≥ 4.5:1, large text ≥ 3:1, icons ≥ 3:1 against their background in both
  themes. Verify `textMuted` on `surface` (target ≥ 4.5:1 for metadata that carries meaning).
- Never encode state by colour alone: pair dots with shape/label, errors with an icon and text.
- Every icon-only button gets `setToolTipText` and `getAccessibleContext().setAccessibleName`.
- Tab order: title bar → rail search → rail list → tasks → transcript → composer → status;
  `F6` remains the fast cycle.
- The composer keeps a visible label/placeholder and an accessible description.
- Dialog focus is trapped and returns to the invoking control; `Esc` closes.
- Text scaling must work to 150% without clipping the 48 px title bar, 52 px session rows, or the
  26 px status bar. Test at 80%, 100%, 125%, 150%.
- Provide a high-contrast option (FlatLaf IntelliJ/Darcula or a custom HC theme) in Settings.

---

## 9. Implementation plan

Each phase ends with a build, the existing desktop tests, and a screenshot at 1280×800 in both
themes. Do not start a phase before the previous one is reviewed.

### Phase 0 — Tokens and theme foundation

Files: new `ui/theme/UiTheme.java`, `NaruDesktop.java`, `ui/SettingsPane.java`,
`model/BlockStyle.java`.

- Define semantic colour, type, spacing, and radius tokens; derive both palettes from one table.
- Add `Follow system` and persist the resolved theme separately from the preference.
- Replace direct `new Color(...)`/hex usage in `MainWindow`, `StatusBar`, `ChatMessageCard`,
  `TranscriptPane`, `InputPane`, `TaskPane`, `ToolsPane`, `PermissionsPane`.
- Map `BlockStyle` to semantic roles while keeping its public API stable.

Acceptance: `grep` finds no hard-coded colours outside the theme/syntax files; switching theme
repaints every open pane without restart; both palettes pass contrast checks.

### Phase 1 — Chrome and rail

Files: `MainWindow.java`, new `ui/IconFactory.java`.

- 48 px title bar, drawn window controls, brand mark, project subtitle.
- 240 px rail with 52 px non-HTML rows, status shapes, search, empty state.
- Consistent action set and copy.
- Remove emoji from chrome.

Acceptance: rail rows render at 80–150% without clipping; keyboard/mouse selection matches;
no HTML in the rail renderer; `Ctrl+N` creates a session.

### Phase 2 — Transcript and composer

Files: `ui/ChatMessageCard.java`, `ui/TranscriptPane.java`, `ui/InputPane.java`,
`ui/ChatPartRenderers.java`, `util/MsgText.java`.

- Flat blocks, hover actions, collapsed tool cards, streaming caret, empty state.
- Composer redesign with model/mode chips, validation, queued tray, send as the only accent fill.
- Keep virtualisation, dedup, echo, and "accept-or-keep" behaviour; add regression tests for each.

Acceptance: 50 000-line budget still passes (`maxFlushMs=0`, `dropped=0` in the focused test);
a rejected line keeps its text; queued and delivered lines appear exactly once; both themes
readable; no emoji.

### Phase 3 — Panels, settings, dialogs

Files: `ui/TaskPane.java`, `ui/ToolsPane.java`, `ui/PermissionsPane.java`, `ui/InspectorPane.java`,
`ui/WorkbenchPane.java`, `ui/SettingsPane.java`, `ui/DesktopDialog.java`.

- Unified panel header/tab treatment, collapsible inspector, themed log tables.
- Settings sections and `Text size` control.
- Empty/loading states, copy pass.

Acceptance: every panel has a title, a way back, and an empty state; no panel uses a raw colour;
`Esc` and focus return work.

### Phase 4 — Polish and verification

- Motion, focus rings, hover, resize feedback, reduced-motion switch.
- Icon set completion; remove remaining emoji and text glyphs.
- Visual regression screenshots: dark/light, 100%/150%, empty/streaming/tool/error states.
- Accessibility pass (accessible names, tab order, contrast report).
- Update `desktop-gui.md` to record the settled visual direction.

Acceptance: the checklist below, plus all focused transcript tests and a clean build.

---

## 10. Acceptance criteria

1. No emoji in production UI strings or window chrome.
2. No hard-coded colour outside `UiTheme` (and syntax tokens in `BlockStyle`).
3. At most six type sizes, all from the token table.
4. All spacing/padding values come from the spacing scale.
5. Both themes meet WCAG AA for body text and controls; state is never colour-only.
6. Rail, title bar, and status bar survive 80–150% text scaling without clipping.
7. Transcript still virtualises; the 50 000-line test passes with `dropped=0`.
8. Message echo, queued echo, storage, dedup, and restore behaviour are unchanged and covered.
9. Every icon-only control has a tooltip and an accessible name; dialogs trap/restore focus.
10. Settings offers theme (including Follow system), text size, and reduce motion.
11. The window has a clear primary action per surface and no more than one accent fill.
12. Copy follows the verb/casing rules in §6.

---

## 11. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Restyling regresses virtualisation or message dedup | Phase 2 lands behind the existing model seams; run the focused transcript tests plus new echo/dedup tests before merging. |
| Theme switch leaves stale colours in custom-painted components | Central `UiTheme` listener + `refreshThemePalette` on every pane; audit all `paintComponent` overrides. |
| Undecorated window feels worse on Linux/Wayland | Keep the custom bar but add resize cursors, snap hints, and native-like hit targets; test on X11 and Wayland. |
| Icon set adds a dependency or licensing burden | Draw the ~24 glyphs as simple `Icon`s first; text labels are an acceptable fallback. |
| Font availability differs per platform | Resolve UI/mono faces through a small fallback chain; never bundle a font without a licence. |
| Scope creep into new features | This plan changes presentation only. New behaviour goes to a separate plan. |

---

## 12. Open decisions

1. **Direction:** A Graphite (recommended), B Paper, or C Native/follow platform.
2. **Accent:** cyan `#38BDF8` (matches the icon) or indigo `#818CF8`.
3. **Default theme:** Follow system (recommended) or Dark.
4. **Icon approach:** drawn 16 px line icons (recommended) or text labels until a set is chosen.
5. **Rail width:** 240 px (recommended) or keep 220 px.
6. **Transcript measure:** 920 px (recommended; readable line length) or full width.

Once 1–3 are confirmed, Phase 0 can start immediately; Phases 1–4 are independent enough to
review separately.
