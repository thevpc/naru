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
        assertEquals("/plan goal \"ship the cockpit\"", PlanDirectives.goal("ship the cockpit"));
        assertEquals("/plan add \"write the rail\"", PlanDirectives.addItem("write the rail"));
        assertEquals("/plan edit item-7 \"do it twice\"", PlanDirectives.editItem("item-7", "do it twice"));
        assertEquals("/plan remove item-7", PlanDirectives.removeItem("item-7"));
        assertEquals("/plan activate 1234abcd-0000", PlanDirectives.activate("1234abcd-0000"));
        assertEquals("/plan deactivate", PlanDirectives.deactivate());
    }

    @Test
    void aDashInTheTextIsStillText() {
        // the board quotes free text precisely so that "--after=x" typed by a reader arrives as
        // prose and not as an option the parser chewed
        assertEquals("/plan add \"support --dry-run in the shell tool\"",
                PlanDirectives.addItem("support --dry-run in the shell tool"));
    }

    @Test
    void quotesAndBackslashesAreEscapedAndSurvive() {
        assertEquals("/plan goal \"say \\\"hello\\\"\"", PlanDirectives.goal("say \"hello\""));
        assertEquals("/plan goal \"a backslash \\\\ and a quote \\\"\"",
                PlanDirectives.goal("a backslash \\ and a quote \""));
    }

    @Test
    void whitespaceBecomesOneSpaceSoTheLineStaysOneStatement() {
        assertEquals("/plan add \"first then second\"", PlanDirectives.addItem("first\n\t then   second"));
        assertEquals("/plan goal \"trimmed\"", PlanDirectives.goal("  trimmed  "));
    }

    @Test
    void nothingToSayIsNoLineAtAll() {
        assertNull(PlanDirectives.goal("   \n  "));
        assertNull(PlanDirectives.addItem(""));
        assertNull(PlanDirectives.addItem(null));
        assertNull(PlanDirectives.editItem("id", " "));
    }

    @Test
    void aDirectiveLineAlwaysStartsWithTheSlash() {
        // a line without it would be delivered to the model as a prompt, which is the bug the
        // composer path already fixed once
        for (String line : new String[]{PlanDirectives.goal("a b"), PlanDirectives.addItem("c"),
                PlanDirectives.removeItem("d"), PlanDirectives.activate("e")}) {
            assertTrue(line.startsWith("/plan "), line);
        }
    }
}
