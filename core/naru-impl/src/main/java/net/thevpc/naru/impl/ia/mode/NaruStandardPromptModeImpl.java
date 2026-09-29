package net.thevpc.naru.impl.ia.mode;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.nuts.util.NBlankable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class NaruStandardPromptModeImpl implements NaruPromptMode {
    public static final NaruPromptMode DEFAULT = new NaruStandardPromptModeImpl(
            NaruPromptMode.DEFAULT,
            new String[]{},
            "",
            t ->
                    true
    );
//    public static final NaruPromptMode ASK = new NaruStandardPromptModeImpl(
//            NaruStandardMode.ASK,
//            new String[]{"chat", "ask", "q"},
//            "You are in ASK MODE.\n" +
//                    "[Goal]: Answer questions and discuss technical concepts.\n" +
//                    "[Rules]:\n" +
//                    "1. READ-ONLY: Discuss design or explain code. Do not write changes.\n" +
//                    "2. Clarify requirements before suggesting structural execution plans.",
//            t ->
//                    !NaruToolTags.EXECUTE.equals(t)
//                            && !NaruToolTags.WRITE.equals(t)
//    );
//
//
//
//    public static final NaruPromptMode REVIEW = new NaruStandardPromptModeImpl(
//            NaruStandardMode.REVIEW,
//            new String[]{"explore", "map"},
//            "You are in REVIEW mode.\n" +
//                    "[Goal]: Explore the directory structure to map and understand user context.\n" +
//                    "[Rules]:\n" +
//                    "1. READ-ONLY: Catalog modules, dependencies, and main entry points.\n" +
//                    "[Output]: Summary of directory architecture and primary technology stacks found.",
//            t ->
//                    !NaruToolTags.EXECUTE.equals(t)
//                            && !NaruToolTags.WRITE.equals(t)
//
//    );
//
//    public static final NaruPromptMode AUDIT = new NaruStandardPromptModeImpl(
//            NaruStandardMode.AUDIT, // Fixed enum binding
//            new String[]{"paranoid", "verify"},
//            "You are in AUDIT mode.\n" +
//                    "[Goal]: Aggressively review written code for bugs before structural commits.\n" +
//                    "[Rules]:\n" +
//                    "1. Scrutinize input validation, edge cases, thread safety, and resource leaks.\n" +
//                    "2. Act as a pedantic code critic. Do not implement features.\n" +
//                    "[Output]: List of security risks, logical gaps, or optimization targets.",
//            t ->
//                    !NaruToolTags.EXECUTE.equals(t)
//                            && !NaruToolTags.WRITE.equals(t)
//
//    );
//
//    public static final NaruPromptMode DEBUG = new NaruStandardPromptModeImpl(
//            NaruStandardMode.DEBUG, // Fixed enum binding
//            new String[]{"trace", "isolate"},
//            "You are in DEBUG mode.\n" +
//                    "[Goal]: Analyze runtime error streams and logs to pinpoint root causes.\n" +
//                    "[Rules]:\n" +
//                    "1. Trace execution flows via stack traces. Do not blindly append code.\n" +
//                    "2. Isolate variables through targeted runtime/test execution scripts.\n" +
//                    "[Output]: Root cause analysis followed by the exact, minimal surgical fix.",
//            t -> !NaruToolTags.WRITE.equals(t)
//
//    );

    private final String name;
    private final String prompt;
    private final Set<String> alias;
    private final Predicate<Set<String>> acceptableToolTags;

    public NaruStandardPromptModeImpl(String name, String[] alias, String prompt, Predicate<Set<String>> acceptableToolTags) {
        this.name = name;
        this.prompt = prompt;
        this.alias = new HashSet<>();
        this.acceptableToolTags = acceptableToolTags;
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
    public boolean acceptToolTags(Set<String> tag) {
        return acceptableToolTags.test(tag);
    }

    @Override
    public String name() {
        return name;
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
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        NaruStandardPromptModeImpl that = (NaruStandardPromptModeImpl) o;
        return Objects.equals(name, that.name) && Objects.equals(prompt, that.prompt) && Objects.equals(alias, that.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, prompt, alias);
    }

    @Override
    public String toString() {
        return String.valueOf(name);
    }
}
