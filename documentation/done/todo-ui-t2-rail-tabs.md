# T2 right (tabbed) panel — done

Moved out of `todo/todo-ui.md` on 2026-11-01.

## The question the todo asked

"Use icons to represent different tabs (first level) and make them visually distinct, add tooltips to
distinguish them. Not sure this will be better, you decide."

## Evidence, before the decision

The rail's strip was painted three ways offscreen in both themes: titles only (as it shipped), glyph +
title, and glyph only.

| Variant | Rows at 320 px | What it costs |
| --- | --- | --- |
| titles | two | selection barely marked; four grey words, no shape to scan for |
| glyph + title | two | ~20 px per tab, which the rail has |
| glyph only | one | ~24 px of height back, but shield/folder/glass/checklist need a hover to name |

**Decided: glyph + title.** Icon-only was the variant that looked like a design win and was not one:
the rail is wide enough that dropping the words saves no row today, and the titles are the key the rest
of the system addresses tabs by — `selectSideTab(String)`, `tabsHolding(String)`, the gate harness that
photographs a tab by name, and the accessible name a screen reader reads. Icon-only would have traded a
self-describing strip for four hover lookups *and* re-plumbed that API. `desktop-ux-refresh.md` §4.4
asks for drawn 16 px line glyphs with text labels explicitly acceptable beside them, which is what
shipped.

## Shipped

- new `ui/TabGlyphs.java`: `Kind { PLAN, PERMISSIONS, INSPECT, WORKSPACE }`, each a 16-px-space
  `Path2D` outline drawn with one round-cap weight and no fills — drawn rather than an icon dependency
  or emoji, because a tab glyph has to take the colour its tab already has.
  `decorate(tabs, index, kind, holds)` sets the glyph and a tooltip that says what is behind the tab,
  and leaves the title alone.
- Colour is borrowed at paint time from `c.getForeground()`, so the glyph follows the theme and follows
  FlatLaf's selected-tab foreground with no listener — the same rule the header learned in T1.
- Size comes from the type ladder (`edgeFor`), so the interface-size slider grows the glyph with the
  word beside it: measured 16 px at 100 % and 21 px at 130 %.
- `MainWindow.sideTabs()`: the four first-level tabs decorated, and `JTabbedPane.tabSelectionHeight`
  raised to 3 px — a one-pixel underline is invisible in a strip that wraps to two rows.
- Second level untouched: the inspector's and workbench's child strips keep plain titles, since they are
  already labelled with their scope ("Tools · task", "Requests · session") and nested glyphs would be
  decoration.

## Gate

- `ui/TabGlyphsTest`: 5 tests, all green — each glyph stays inside the box it declared and paints real
  ink; the four are pairwise more than 40 % apart in ink, so they are four shapes and not one; the glyph
  comes back in whatever foreground the tab wears; the box follows the ladder and is clamped so it never
  shrinks below a readable shield; `decorate` adds glyph and tooltip and leaves `getTitleAt` untouched,
  which is the guard for by-title selection.
- `mvn -o -pl app/naru-desktop test`: **272 tests, 266 green**. The only reds are the two that predate
  this work and reproduce at clean `HEAD`: `SplitPanesTest` (1 failure) and `BudgetTextTest` (5 errors).
- The strip was painted through the production `decorate` in light and dark at 100 % and 130 %, with the
  second tab selected, and checked by eye.

## Deviations

- **No live-window capture in this gate**, unlike T1. Driving the desktop means moving and clicking a
  window over the owner's live session on a shared three-monitor display — one attempt already landed a
  click in his editor. The strip is a paint-only surface, so the offscreen render through the real
  decoration pass is the same pixels without the intrusion.
- **`edgeFor` is a seam the plan did not call for.** The ladder rule cannot be tested through `edge()`
  because `NaruDesktop.fontScale()` reads preferences; splitting the rule out tests it directly and pins
  `edge()` to it.
- One suite run showed 11 extra desktop reds (`NoClassDefFoundError` on core classes). They are a race,
  not a regression: `naru-api-1.1.0.0.jar` was reinstalled at 17:03 while the suite was mid-flight. The
  re-run after that build settled is at the baseline above.
