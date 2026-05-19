package net.thevpc.naru.api.skills;

import net.thevpc.naru.api.agent.NAruVisibility;

import java.util.List;
import java.util.function.IntPredicate;

public interface NaruSkill {
    NAruVisibility getVisibility();
    String getName();

    List<String> getLines();

    List<String> getLines(IntPredicate lineFilter);

    String getFormattedText();

    boolean isEmpty();
}
