package net.thevpc.naru.ext.coder;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

/**
 * Relevance gating for the coding instructions.
 *
 * <p>The whole reason the directive lives in an extension is that it must reach a
 * coding task and nothing else. These tests pin both directions: executing intent
 * and the {@code write}/{@code exec}/{@code fs} capabilities turn it on, a
 * planning mode and a plain read-only chat leave it off (zero messages).
 */
public class NaruCoderExtensionTest {

    private final NaruCoderExtension ext = new NaruCoderExtension();

    // ── on ─────────────────────────────────────────────────────────────────

    @Test
    public void executingIntentIsRelevant() {
        NaruTask task = task(mode(NaruPromptMode.ModeIntent.EXECUTING), Set.of());
        Assertions.assertTrue(ext.isRelevant(task));
        Assertions.assertEquals(1, ext.contribute(task).size());
    }

    @Test
    public void eachCodingCapabilityMakesAGeneralTaskRelevant() {
        for (String tag : List.of("write", "exec", "fs")) {
            NaruTask task = task(mode(NaruPromptMode.ModeIntent.GENERAL), Set.of(tag));
            Assertions.assertTrue(ext.isRelevant(task), tag);
            Assertions.assertEquals(1, ext.contribute(task).size(), tag);
        }
    }

    @Test
    public void capabilitiesAreMatchedCaseInsensitivelyAndTrimmed() {
        NaruTask task = task(mode(NaruPromptMode.ModeIntent.GENERAL), Set.of(" WRITE "));
        Assertions.assertTrue(ext.isRelevant(task));
    }

    @Test
    public void aTaskWithNoModeFallsBackToItsCapabilities() {
        Assertions.assertTrue(ext.isRelevant(task(null, Set.of("write"))));
        Assertions.assertFalse(ext.isRelevant(task(null, Set.of())));
    }

    // ── off ────────────────────────────────────────────────────────────────

    @Test
    public void planningIsNeverRelevantEvenWithCodingCapabilities() {
        NaruTask task = task(mode(NaruPromptMode.ModeIntent.PLANNING), Set.of("write", "exec", "fs"));
        Assertions.assertFalse(ext.isRelevant(task),
                "a plan is a document; the workflow rules would only invite premature edits");
        Assertions.assertTrue(ext.contribute(task).isEmpty());
    }

    @Test
    public void aGeneralTaskWithoutCapabilitiesContributesNothing() {
        NaruTask task = task(mode(NaruPromptMode.ModeIntent.GENERAL), Set.of());
        Assertions.assertFalse(ext.isRelevant(task));
        Assertions.assertTrue(ext.contribute(task).isEmpty());
    }

    @Test
    public void unrelatedCapabilitiesDoNotCountAsCoding() {
        NaruTask task = task(mode(NaruPromptMode.ModeIntent.GENERAL), Set.of("network", "git", "ai", "semantic"));
        Assertions.assertFalse(ext.isRelevant(task));
    }

    @Test
    public void aNullTaskContributesNothing() {
        Assertions.assertFalse(ext.isRelevant(null));
        Assertions.assertTrue(ext.contribute(null).isEmpty());
    }

    // ── the contributed text ─────────────────────────────────────────────────

    @Test
    public void theExactCodingPromptIsContributed() {
        NaruMessage m = ext.contribute(task(mode(NaruPromptMode.ModeIntent.EXECUTING), Set.of())).get(0);
        Assertions.assertEquals(NaruCoderExtension.CODING_PROMPT, m.getContent());
        Assertions.assertEquals(NaruCoderExtension.NAME, m.getSourceName(),
                "the source is how /context attributes the message");
    }

    @Test
    public void thePromptKeepsTheRequiredWorkflowAndRules() {
        String p = NaruCoderExtension.CODING_PROMPT;
        for (String phrase : new String[]{
                "expert autonomous software engineer",
                "## Execution Workflow",
                "1. Explore:",
                "2. Implement:",
                "3. Verify:",
                "4. Iterate:",
                "Never leave placeholders",
                "## Core Rules & Constraints",
                "Autonomy:",
                "Strict Constraints:",
                "Non-Destructive:"}) {
            Assertions.assertTrue(p.contains(phrase), phrase);
        }
    }

    // ── mocks ────────────────────────────────────────────────────────────────

    private static NaruTask task(NaruPromptMode promptMode, Set<String> tags) {
        return (NaruTask) Proxy.newProxyInstance(
                NaruCoderExtensionTest.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "promptMode":
                            return promptMode;
                        case "findToolTagNames":
                            return tags;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static NaruPromptMode mode(NaruPromptMode.ModeIntent intent) {
        return (NaruPromptMode) Proxy.newProxyInstance(
                NaruCoderExtensionTest.class.getClassLoader(),
                new Class<?>[]{NaruPromptMode.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "modeIntent":
                            return intent;
                        case "name":
                            return "test";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
