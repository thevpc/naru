package net.thevpc.naru.ext.tools.plan.modes;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.registry.NaruToolTags;

import java.util.Set;

public class PlanNaruPromptMode implements NaruPromptMode {
    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String[] aliases() {
        return new String[]{"planning", "architect"};
    }

    @Override
    public String systemPrompt() {
        return "You are in PLANNING MODE (PLAN).\n" +
                "[Goal]: Analyze code and produce a milestone blueprint.\n" +
                "[Rules]:\n" +
                "1. READ-ONLY: Use only file/directory view tools. No file edits or code execution.\n" +
                "2. PLAN FIRST: Before any analysis, call plan_create with a goal and an ordered list of concrete steps.\n" +
                "3. KEEP IT LIVE: Call plan_update as you start, complete, or get blocked on each step.\n" +
                "4. FAIL-NEVER: Proactively identify state, dependency, and runtime edge cases as plan steps.\n" +
                "[Output]: Context Constraints -> Risk Analysis -> Step-by-Step Milestones (mirroring the active plan).";
    }

    @Override
    public boolean acceptToolTags(Set<String> tags) {
        return !tags.contains(NaruToolTags.EXECUTE)
                && !tags.contains(NaruToolTags.WRITE);
    }


}
