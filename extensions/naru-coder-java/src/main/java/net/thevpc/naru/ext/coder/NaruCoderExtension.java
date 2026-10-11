package net.thevpc.naru.ext.coder;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Contributes autonomous software-engineering instructions when a task is a
 * coding task.
 *
 * <p>The core runtime is task-agnostic and its base system prompt is
 * deliberately minimal. A model doing engineering work still needs to be told
 * how to behave: explore before editing, keep going instead of stopping after
 * reading, verify with tests, and respect negative constraints. Those rules are
 * a <em>coding</em> feature, not a runtime one, so they live here — removing
 * this jar removes them, and a plain chat never sees them.
 *
 * <h2>Relevance</h2>
 * The instructions are spliced in only when the task is actually doing code:
 *
 * <ul>
 *   <li>a {@link NaruPromptMode.ModeIntent#EXECUTING} mode is always relevant;
 *       a {@link NaruPromptMode.ModeIntent#PLANNING} mode never is (a plan is a
 *       document, and the workflow rules would only invite premature edits);</li>
 *   <li>otherwise the task's granted tool tags must include one of
 *       {@code write}, {@code exec} or {@code fs} — the capabilities without
 *       which the workflow could not be carried out anyway.</li>
 * </ul>
 *
 * <p>A task with none of the above (simple chat, a read-only question, a
 * general-purpose mode with no coding tags) contributes nothing.
 */
public class NaruCoderExtension implements NaruSessionExtension {

    public static final String NAME = "coder";

    /**
     * The coding capabilities whose presence makes this extension relevant.
     * Taken from {@link NaruToolTags} so a rename there cannot drift.
     */
    public static final Set<String> CODING_TAGS = Set.of(
            NaruToolTags.WRITE,
            NaruToolTags.EXECUTE,
            NaruToolTags.FILE_SYSTEM
    );

    /**
     * The compact autonomous-engineering directive. Kept short (~350 tokens) and
     * stable: it is model-facing text, so changing it changes behaviour.
     */
    public static final String CODING_PROMPT = """
            You are an expert autonomous software engineer. Your objective is to completely solve the user's request in the project directory.

            ## Execution Workflow
            1. Explore: Find and read relevant files to understand context, architecture, and existing conventions before editing.
            2. Implement: Make precise, working code modifications using the provided tools. Never leave placeholders or incomplete implementations.
            3. Verify: Always run tests or validation commands (via shell) to confirm your changes pass and introduce no regressions.
            4. Iterate: If tests fail or errors occur, inspect the failure, fix the root cause, and re-verify until all pass.

            ## Core Rules & Constraints
            - Autonomy: Do not stop after merely exploring or reading files. Continue using tools until the task is completely implemented and verified. Only provide a final text response when all work is finished.
            - Strict Constraints: Respect all negative constraints in the prompt (e.g., if asked "Do not edit files in tests/", never touch test files).
            - Non-Destructive: Preserve existing code style, formatting, and unrelated functionality. Avoid overwriting entire files when surgical edits suffice.
            """;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Runs after core contributors (default {@code order() == 100}) so coding
     * instructions follow the base system material.
     */
    @Override
    public int order() {
        return 100;
    }

    @Override
    public Set<NaruSource> sources() {
        return EnumSet.of(NaruSource.SYSTEM);
    }

    @Override
    public NaruSource source() {
        return NaruSource.SYSTEM;
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        if (task == null) {
            return false;
        }
        NaruPromptMode mode = task.promptMode();
        if (mode != null) {
            NaruPromptMode.ModeIntent intent = mode.modeIntent();
            if (intent == NaruPromptMode.ModeIntent.PLANNING) {
                return false;
            }
            if (intent == NaruPromptMode.ModeIntent.EXECUTING) {
                return true;
            }
        }
        return hasCodingCapability(task);
    }

    @Override
    public List<NaruMessage> contribute(NaruTask task) {
        if (!isRelevant(task)) {
            return Collections.emptyList();
        }
        return List.of(NaruMessage.system(CODING_PROMPT).setSourceName(NAME));
    }

    /**
     * Whether the task holds one of {@link #CODING_TAGS}, as names (a tag whose
     * provider is not installed is still a granted capability and still counts).
     */
    private static boolean hasCodingCapability(NaruTask task) {
        Set<String> tags = task.findToolTagNames();
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (tag != null && CODING_TAGS.contains(tag.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
