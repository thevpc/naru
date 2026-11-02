package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lines the board sends, and the quoting that keeps a sentence intact on the way through the
 * command-line parser.
 *
 * <p>Tested apart from the panel because the rules come from a parser that is not ours: a token ends
 * at every unquoted space, a word that starts with {@code --} is read as an option, and inside
 * quotes {@code \"} is the escape the tokenizer accepts. A reader pasting a description with a dash
 * in it should not have to know either fact, so the composer of the line has to.
 */
class PlanDirectivesTest {

    @Test
    void eachControlIsTheDirectiveAPersonWouldType() {
        assertEquals("/plan create \"ship the cockpit\"", PlanDirectives.create("ship the cockpit"));
        assertEquals("/plan goal --plan=active-plan \"ship the cockpit\"",
                PlanDirectives.goal("active-plan", "ship the cockpit"));
        assertEquals("/plan add --plan=active-plan \"write the rail\"",
                PlanDirectives.addItem("active-plan", "write the rail"));
        assertEquals("/plan edit --plan=active-plan item-7 \"do it twice\"",
                PlanDirectives.editItem("active-plan", "item-7", "do it twice"));
        assertEquals("/plan remove --plan=active-plan item-7",
                PlanDirectives.removeItem("active-plan", "item-7"));
        assertEquals("/plan activate 1234abcd-0000", PlanDirectives.activate("1234abcd-0000"));
        assertEquals("/plan deactivate", PlanDirectives.deactivate());
    }

    @Test
    void aDashInTheTextIsStillText() {
        // the board quotes free text precisely so that "--after=x" typed by a reader arrives as
        // prose and not as an option the parser chewed
        assertEquals("/plan add --plan=p1 \"support --dry-run in the shell tool\"",
                PlanDirectives.addItem("p1", "support --dry-run in the shell tool"));
    }

    @Test
    void quotesAndBackslashesAreEscapedAndSurvive() {
        assertEquals("/plan goal --plan=p1 \"say \\\"hello\\\"\"",
                PlanDirectives.goal("p1", "say \"hello\""));
        assertEquals("/plan goal --plan=p1 \"a backslash \\\\ and a quote \\\"\"",
                PlanDirectives.goal("p1", "a backslash \\ and a quote \""));
    }

    @Test
    void whitespaceBecomesOneSpaceSoTheLineStaysOneStatement() {
        assertEquals("/plan add --plan=p1 \"first then second\"",
                PlanDirectives.addItem("p1", "first\n\t then   second"));
        assertEquals("/plan goal --plan=p1 \"trimmed\"", PlanDirectives.goal("p1", "  trimmed  "));
    }

    @Test
    void nothingToSayIsNoLineAtAll() {
        assertNull(PlanDirectives.create("   \n  "));
        assertNull(PlanDirectives.goal("p1", "   \n  "));
        assertNull(PlanDirectives.addItem("p1", ""));
        assertNull(PlanDirectives.addItem("p1", null));
        assertNull(PlanDirectives.editItem("p1", "id", " "));
    }

    @Test
    void aDirectiveLineAlwaysStartsWithTheSlash() {
        // a line without it would be delivered to the model as a prompt, which is the bug the
        // composer path already fixed once
        for (String line : new String[]{PlanDirectives.create("a b"), PlanDirectives.goal("p", "a b"),
                PlanDirectives.addItem("p", "c"), PlanDirectives.removeItem("p", "d"),
                PlanDirectives.activate("e")}) {
            assertTrue(line.startsWith("/plan "), line);
        }
    }
}
