package net.thevpc.naru.desktop.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prompt parser, checked against the exact text {@code AskUserTool} builds — the engine has
 * no structured choices field, so this is the only way a button can appear where the model meant
 * one.
 */
class InputPromptTest {

    @Test
    void parsesTheAskUserPromptShape() {
        String raw = "Which file should I edit?\n"
                + "1. src/Main.java\n"
                + "2. src/App.java\n"
                + "3. both\n"
                + "Reply with a number or choice, or type another answer.";
        InputPrompt p = InputPrompt.parse(raw);
        assertEquals("Which file should I edit?", p.question());
        assertEquals(3, p.choices().size());
        assertEquals("src/Main.java", p.choices().get(0));
        assertEquals("both", p.choices().get(2));
        assertEquals("Reply with a number or choice, or type another answer.", p.trailingHint());
    }

    @Test
    void aQuestionWithoutChoicesStaysPlainText() {
        InputPrompt p = InputPrompt.parse("What is the deploy target?");
        assertFalse(p.hasChoices());
        assertEquals("What is the deploy target?", p.question());
    }

    @Test
    void proseThatHappensToContainANumberIsNotAChoice() {
        // numbering must run 1,2,3 from the first numbered line, not from wherever a digit appears
        InputPrompt p = InputPrompt.parse("I checked 2. the file and 3. the log. What next?");
        assertFalse(p.hasChoices());
    }

    @Test
    void anEmptyPromptIsNotAnError() {
        InputPrompt p = InputPrompt.parse("");
        assertEquals("", p.question());
        assertFalse(p.hasChoices());
        InputPrompt q = InputPrompt.parse(null);
        assertTrue(q.choices().isEmpty());
    }

    @Test
    void twelveChoicesIsTheEngineMaximum() {
        StringBuilder sb = new StringBuilder("Pick one:\n");
        for (int i = 1; i <= 12; i++) {
            sb.append(i).append(". option ").append(i).append('\n');
        }
        sb.append("Reply with a number or choice, or type another answer.");
        InputPrompt p = InputPrompt.parse(sb.toString());
        assertEquals(12, p.choices().size());
        assertEquals("option 12", p.choices().get(11));
    }
}
