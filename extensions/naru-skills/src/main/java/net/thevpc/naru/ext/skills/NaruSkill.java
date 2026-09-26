package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NAruVisibility;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * A named block of instructions loaded from a markdown file under
 * {@code <project>/.naru/skills} (public) or {@code <project>/.naru/local/skills} (private).
 * <p>
 * Part of the optional {@code naru-skills} extension: the core neither knows this type nor
 * injects it into prompts.
 */
public interface NaruSkill {
    NAruVisibility getVisibility();

    String getName();

    /**
     * Where the content came from, for provenance in {@code /context} listings. A single
     * path when one file supplied the skill, or the set of paths when a public and a
     * private copy were merged.
     */
    String getSourceName();

    List<String> getLines();

    List<String> getLines(IntPredicate lineFilter);

    String getFormattedText();

    boolean isEmpty();
}
