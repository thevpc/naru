# Investigation Report: NARU Skills Redesign

## 3.1 Cache Segment Placement

The caching splitter in `NaruTaskImpl` (lines 1452-1456) partitions messages by source:
```java
for (NaruMessage m : all) {
    if (m.getSource() == NaruSource.USER) {
        conversation.add(m);
    } else {
        stable.add(m);
    }
}
```

Currently, skills contribute with `NaruSource.SKILL` (from `NaruSkillsExtension.source()`), which places them in the stable `system-context` segment.

To make activation messages land in the turn/conversation segment (avoiding cache invalidation), they must have source `NaruSource.USER`.

**Options from section 3.1:**
1. Give activation messages a source that splitter treats as positional/turn-level - Not possible; splitter only treats `USER` specially
2. Small generic core change (e.g., "message placement: STABLE_PREFIX vs IN_ORDER") - Would require modifying the splitter logic
3. Use `USER` source with `sourceName` `activation:<name>` - Viable without core changes

**Recommendation:** Use option 3 - activation messages with `NaruSource.USER` and `sourceName` `activation:<name>`. This requires no core changes and satisfies the requirement that activation messages appear in turn segments.

## 3.2 Compactor Contract

The compactor (`NaruCompactContextCompactor`) processes a source view and:
1. Calculates a cut point based on window spec (items/turns/tokens from end)
2. Summarizes items [0..cut) as "covered"
3. Leaves items [cut..end) unchanged
4. Builds new history: summary message + covered items (flagged with `excludedBy`) + uncovered items

**Current behavior regarding message preservation:**
- Summary items (`m.isSummary()`) are never flagged as excluded, even if in covered range
- All other messages in covered range are flagged as excluded and removed from context view
- The compactor has no mechanism to preserve specific message types verbatim
- No extension hook exists for post-compaction re-emission

**Options:**
- Ask compactor to preserve activation messages verbatim - Requires core change to compactor
- Have extension re-emit activation messages after compaction - Possible via extension's `contribute` method
- Use `USER` source and rely on window keeping recent items - Unreliable; depends on window size

**Recommendation:** Extension will re-emit activation messages during `contribute` by scanning history for activation messages and ensuring they're represented. This requires no core changes and aligns with section 2.4's "Preferred mechanism: ... exposes a hook to re-emit them" (where the hook is the extension's own `contribute` method).

## 3.3 Directive Appending Messages to History

Directives can append messages to history via:
1. `NaruTask.addHistory(NaruMessage)` - Adds assistant or user messages to history
2. This is used by the engine when processing model outputs and user input

For `/skill use` to append a user-role message at the tail:
- Create a `NaruMessage` with `NaruRole.user`, content containing activation framing
- Set source to `NaruSource.USER` and sourceName to `activation:<name>`
- Call `task.addHistory(message)` from the directive handler

This is the standard, supported way for directives to contribute to conversation history.

## 3.4 Test That Clears History

Current test (`NaruSkillsToolTest.aLoadedSkillIsReinjectedAfterTheHistoryIsCompacted()`):
1. Loads skill via extension (`ext.load(t, "git-flow")`) 
2. Verifies skill body present in extension's `contribute()`
3. Clears task history (`t.clearHistory()`)
4. Verifies skill body still present in `contribute()` 
5. Relies on fact that skills are NOT in history - they're contributed fresh each request

**New test requirements:**
- Activation adds user-role message to history via `task.addHistory()`
- After history clearance/compaction, activation message should be:
  - Either still in history (if in uncovered window)
  - Or re-emitted by extension in `contribute()` (if was summarized)
- Test must verify activation message appears in history or is re-emitted after compaction
- Must assert that `system-context` segment content does NOT change when skill is activated

## Phase 0 Decision Summary

**Core Changes Required:** NO
- Activation messages will use `NaruSource.USER` with `sourceName` `activation:<name>`
- Extension will handle activation logic and re-emission after compaction via `contribute` method
- No modifications to core splitter or compactor needed

**Approach:**
1. Activation messages: user-role, `NaruSource.USER`, `sourceName` `activation:<name>`
2. Shared activation service handles resolution, gating, already-active check, framing
3. Extension `contribute` emits only catalog (tiered, alphabetical)
4. Extension `contribute` also scans history for activation messages and ensures they're represented
5. `/skill use` directive adds activation message to history via `task.addHistory()`
6. Migration: Convert `schemaVersion: 1/2` LOADED entries to pending activations delivered via normal activation path

**Verification that ext/skills.tson can be dropped:**
Yes - activation state (which skills are active) can be derived from history by scanning for messages with `sourceName` starting with `activation:`. The compactor will preserve these messages as regular history items, and the extension can re-emit any that get summarized.

