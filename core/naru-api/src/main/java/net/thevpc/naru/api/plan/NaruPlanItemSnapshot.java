package net.thevpc.naru.api.plan;

import java.util.List;

/**
 * One item of a plan, as a host sees it.
 *
 * <p>Dependencies are carried as the declaration keys the plan was written with rather than as item
 * UUIDs: {@code "b (after a)"} is readable in a table, and a host that has to render a graph has no
 * use for an opaque handle it cannot show. An item declared without a key falls back to the same
 * short id prefix {@code /plan show} prints, so the label is never empty.
 *
 * @param id           the item's stable id
 * @param key          its declaration key, or an id prefix when it was declared without one
 * @param description  what the item is for
 * @param state        where it stands
 * @param dependsOnKeys the keys of the items that must finish first, in declaration order
 * @param notes        the last note reported against it, may be blank
 * @param taskId       the task currently working on it, or {@code null}
 * @param attempts     how many times it has been started
 */
public record NaruPlanItemSnapshot(String id, String key, String description,
                                   NaruPlanItemState state, List<String> dependsOnKeys,
                                   String notes, Long taskId, int attempts) {
}
