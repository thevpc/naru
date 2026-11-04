# T1 header — done

Moved out of `todo/todo-ui.md` on 2026-11-01.

## Plan as executed

**Was.** `MainWindow.topBar()` built the whole chrome on a plain `Panel.background` with a 1 px matte
border: `JMenuBar` at WEST, an empty Centre strip as the drag handle, four glyph buttons at EAST. The
only thing saying "this is NARU" was the taskbar icon.

**Shipped.**
1. *Brand wash.* `ui/HeaderBar.java` paints a horizontal gradient under the chrome — `UiPalette.BRAND`
   mixed into the theme's own surface at the left, thinning to the surface before the controls — plus a
   two-row lit hairline in place of the matte border. Strength is theme-aware (0.22 dark / 0.13 light)
   and every colour is resolved at paint time, so a theme or size change moves the bar with the rest of
   the interface without a rebuild.
2. *Brand mark.* `ui/BrandMark.java`: the banner artwork (`naru-logo-banner.png`) cropped once to its
   own alpha bounds, resampled to a 256px-tall cache, and drawn as a badge that rules the bar's height —
   1.55× the `UiFonts.HEADING` line, never below 26px — at its own 4:1 aspect. The raven, "NARU" and the
   kana are one piece now, replacing the earlier icon-plus-letterspaced word; the interface-size slider
   still moves the mark, through the same font. It is a `JLabel`, so the name is still the accessible
   name even where the artwork cannot be read.
3. *Hover-only colour.* `ui/HoverTint.java`: a rounded pill in the control's own hue, crossed through
   `HoverFader` — settings → accent, minimize → amber, maximize → green, close → red — with the glyph
   taking the same hue at full hover. Justified against the palette rule ("colour carries state"): a
   hover hue exists only under the pointer, so it cannot be read as a state dot.
4. *Chrome out of `MainWindow`.* The three pieces above are paintable offscreen, which is what let the
   bar be tested at all; `MainWindow.topBar()` now only assembles them.

`ui/UiPalette.java` gained `BRAND` (`#38BDF8`), documented as brand and never a state.

## What differed from the plan

- **`HeaderBar.washedMenuBar()` was not in the plan.** Acceptance found the light theme drawing a pure
  white rectangle behind File/View/Session/Help: FlatLaf keeps the window background on a `JMenuBar` as
  a `UIResource` and fills that rectangle even when the bar is non-opaque. Clearing opacity alone does
  not help; the bar needs a plain transparent colour, which makes the UI's fill a no-op and survives a
  theme change. Extracted into a named factory with a test, because the fix is two lines that read as
  redundant and would otherwise be tidied away.
- **`HoverTint` lost its `of(...)`/`style(...)` factories.** A constructor with `(glyph, hue, tip)` was
  enough — the plan's second variant existed for task buttons, and converting those is not part of T1.
- **No `refreshThemePalette()` hook for the mark.** The plan expected to re-scale it on a theme or size
  pass; both mark and colours resolve from the font ladder as they paint, so there is nothing to refresh.

## Gate

- `mvn -o -pl app/naru-desktop test`: **267 tests, 262 green**. The 1 failure (`SplitPanesTest`) and 5
  errors (`BudgetTextTest`) reproduce identically in a clean worktree of `HEAD`, so they predate this
  work.
- `ui/HeaderBarTest`: 16 tests, all green — wash leads with the brand and fades before the controls,
  hairline lit and confined to the bottom rows, menus let the wash through (both directions), drag and
  double-click semantics intact, one toggle per real double-click, controls tint their own hue and hand
  their foreground back, mark bounded by its own alpha and growing with the font.
- Live window (`DISPLAY=:0`, isolated `java.util.prefs.userRoot` so no shared preference was touched):
  light and dark themes at 100 % and 130 %, brand mark, wash under the menus, lit hairline, all four
  hover pills, and an open menu popup all verified on screen.
