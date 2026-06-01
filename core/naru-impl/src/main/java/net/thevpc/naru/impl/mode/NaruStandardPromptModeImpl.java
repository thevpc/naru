package net.thevpc.naru.impl.mode;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NOptional;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class NaruStandardPromptModeImpl implements NaruPromptMode {
    public static final NaruPromptMode ASK = new NaruStandardPromptModeImpl(
            NaruStandardMode.ASK,
            new String[]{"chat", "ask", "q"},
            "You are in ASK MODE.\n" +
                    "[Goal]: Answer questions and discuss technical concepts.\n" +
                    "[Rules]:\n" +
                    "1. READ-ONLY: Discuss design or explain code. Do not write changes.\n" +
                    "2. Clarify requirements before suggesting structural execution plans."
    );

    public static final NaruPromptMode PLANNING = new NaruStandardPromptModeImpl(
            NaruStandardMode.PLANNING,
            new String[]{"plan", "architect"},
            "You are in PLANNING MODE (PLAN).\n" +
                    "[Goal]: Analyze code and output a milestone blueprint.\n" +
                    "[Rules]:\n" +
                    "1. READ-ONLY: Use only file/directory view tools. No file edits or code execution.\n" +
                    "2. FAIL-NEVER: Proactively identify state, dependency, and runtime edge cases.\n" +
                    "[Output]: Context Constraints -> Risk Analysis -> Step-by-Step Milestones."
    );

    public static final NaruPromptMode IMPLEMENT = new NaruStandardPromptModeImpl(
            NaruStandardMode.IMPLEMENT,
            new String[]{"impl", "do"},
            "You are in IMPLEMENT mode.\n" +
                    "[Goal]: Systematically implement the provided structural plan.\n" +
                    "[Rules]:\n" +
                    "1. Change exactly one milestone step at a time.\n" +
                    "2. Run test/compile tools immediately after editing any file.\n" +
                    "3. If exit code != 0, stop and fix before moving to the next step."
    );

    public static final NaruPromptMode REVIEW = new NaruStandardPromptModeImpl(
            NaruStandardMode.REVIEW,
            new String[]{"explore", "map"},
            "You are in REVIEW mode.\n" +
                    "[Goal]: Explore the directory structure to map and understand user context.\n" +
                    "[Rules]:\n" +
                    "1. READ-ONLY: Catalog modules, dependencies, and main entry points.\n" +
                    "[Output]: Summary of directory architecture and primary technology stacks found."
    );

    public static final NaruPromptMode AUDIT = new NaruStandardPromptModeImpl(
            NaruStandardMode.AUDIT, // Fixed enum binding
            new String[]{"paranoid", "verify"},
            "You are in AUDIT mode.\n" +
                    "[Goal]: Aggressively review written code for bugs before structural commits.\n" +
                    "[Rules]:\n" +
                    "1. Scrutinize input validation, edge cases, thread safety, and resource leaks.\n" +
                    "2. Act as a pedantic code critic. Do not implement features.\n" +
                    "[Output]: List of security risks, logical gaps, or optimization targets."
    );

    public static final NaruPromptMode DEBUG = new NaruStandardPromptModeImpl(
            NaruStandardMode.DEBUG, // Fixed enum binding
            new String[]{"trace", "isolate"},
            "You are in DEBUG mode.\n" +
                    "[Goal]: Analyze runtime error streams and logs to pinpoint root causes.\n" +
                    "[Rules]:\n" +
                    "1. Trace execution flows via stack traces. Do not blindly append code.\n" +
                    "2. Isolate variables through targeted runtime/test execution scripts.\n" +
                    "[Output]: Root cause analysis followed by the exact, minimal surgical fix."
    );

    private final NaruStandardMode standardMode;
    private final String prompt;
    private final Set<String> alias;

    public NaruStandardPromptModeImpl(NaruStandardMode standardMode, String[] alias, String prompt) {
        this.standardMode = standardMode;
        this.prompt = prompt;
        this.alias = new HashSet<>();
        if (alias != null) {
            for (String s : alias) {
                // Assuming NBlankable is part of your local framework utilities
                if (!NBlankable.isBlank(s)) {
                    this.alias.add(s.toLowerCase().trim()); // Clean aliases for easier routing
                }
            }
        }
    }

    @Override
    public String name() {
        return standardMode.name();
    }

    @Override
    public String systemPrompt() {
        return prompt;
    }

    @Override
    public String[] aliases() {
        return alias.toArray(new String[0]);
    }

    @Override
    public NOptional<NaruStandardMode> asStandardMode() {
        return NOptional.of(standardMode);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruStandardPromptModeImpl that = (NaruStandardPromptModeImpl) o;
        return standardMode == that.standardMode && Objects.equals(prompt, that.prompt) && Objects.equals(alias, that.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(standardMode, prompt, alias);
    }

    @Override
    public String toString() {
        return standardMode.name();
    }
}
