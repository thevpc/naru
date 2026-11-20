package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * The view axis, kept out of the project's files.
 *
 * <p>Each test uses its own temporary project path, so the settings remembered for one case cannot
 * answer for another, and {@code clear()} leaves nothing behind in the user's preferences.
 */
class ModelViewPreferencesTest {

    private NPath project;
    private ModelViewPreferences prefs;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    private ModelViewPreferences forNewProject() {
        project = NPath.ofTempFolder("naru-model-view-" + System.nanoTime()).resolve("project");
        prefs = ModelViewPreferences.of(project);
        return prefs;
    }

    @AfterEach
    void tearDown() {
        if (prefs != null) {
            prefs.clear();
        }
    }

    @Test
    void hidingAProviderDropsItAndItsModelsAndUnHidingRestoresBoth() {
        ModelViewPreferences p = forNewProject();
        p.hideProvider("ollama", true);

        Assertions.assertTrue(p.isHidden("ollama", "llama3:8b"), "the provider covers its models");
        Assertions.assertEquals(Set.of("ollama"), p.hiddenProviders());
        Assertions.assertTrue(p.hiddenModels().isEmpty(),
                "hiding a provider is not the same as hiding one of its models");

        p.hideProvider("ollama", false);
        Assertions.assertFalse(p.isHidden("ollama", "llama3:8b"));
        Assertions.assertTrue(p.hiddenProviders().isEmpty());
    }

    @Test
    void theSpellingOnDiskIsTheOneTheReaderTypedFirst() {
        ModelViewPreferences p = forNewProject();
        p.hideProvider("Ollama", true);
        p.hideProvider("ollama", true);

        Assertions.assertEquals(List.of("Ollama"), List.copyOf(p.hiddenProviders()),
                "the same provider hidden twice is still one entry, not two rows to remove");
        Assertions.assertTrue(p.isHidden("OLLAMA", "anything"), "and the match ignores case either way");
    }

    @Test
    void oneModelCanBeHiddenWithoutTouchingItsProvider() {
        ModelViewPreferences p = forNewProject();
        p.hideModel("openrouter", "deepseek-v3", true);

        Assertions.assertTrue(p.isHidden("openrouter", "deepseek-v3"));
        Assertions.assertFalse(p.isHidden("openrouter", "claude-sonnet"),
                "the rest of the provider is still in the listing");
        Assertions.assertEquals(Set.of("openrouter/deepseek-v3"), p.hiddenModels());
        Assertions.assertTrue(p.hiddenProviders().isEmpty());
    }

    @Test
    void theSettingsAreStillThereWhenTheNextWindowAsks() {
        ModelViewPreferences p = forNewProject();
        p.hideProvider("groq", true);
        p.setFilter("text", "deepseek");
        p.setFilter("tools", "true");

        // a fresh lookup stands in for the next launch of the same project: nothing is remembered in
        // this instance's fields, only what went into the preferences
        ModelViewPreferences again = ModelViewPreferences.of(project);
        Assertions.assertTrue(again.isHidden("groq", "llama"));
        Assertions.assertEquals("deepseek", again.filter("text"));
        Assertions.assertEquals("true", again.filter("tools"));
        Assertions.assertNull(again.filter("free"), "a facet never set stays unset");
    }

    @Test
    void anotherProjectDoesNotInheritThisProjectsChoices() {
        ModelViewPreferences p = forNewProject();
        p.hideProvider("openai", true);

        NPath other = NPath.ofTempFolder("naru-model-view-other-" + System.nanoTime()).resolve("project");
        ModelViewPreferences elsewhere = ModelViewPreferences.of(other);
        Assertions.assertFalse(elsewhere.isHidden("openai", "gpt-4o"),
                "the provider hidden while working offline is the one wanted while debugging remote");
        elsewhere.clear();
    }

    @Test
    void aBlankFacetIsForgottenRatherThanStoredEmpty() {
        ModelViewPreferences p = forNewProject();
        p.setFilter("text", "qwen");
        Assertions.assertEquals("qwen", p.filter("text"));

        p.setFilter("text", "   ");
        Assertions.assertNull(p.filter("text"),
                "an empty filter and an unset one are the same thing to the picker");
    }

    @Test
    void withNoProjectOpenNothingIsRememberedAndNothingIsHidden() {
        ModelViewPreferences p = ModelViewPreferences.of(null);
        Assertions.assertFalse(p.isPersistent(), "there is no project slot to write under");

        p.hideProvider("ollama", true);
        p.setFilter("text", "qwen");

        Assertions.assertTrue(p.hiddenProviders().isEmpty());
        Assertions.assertFalse(p.isHidden("ollama", "llama3"));
        Assertions.assertNull(p.filter("text"));
    }

    @Test
    void aListingLosesTheHiddenProviderAndKeepsTheRest() {
        ModelViewPreferences p = forNewProject();
        List<NaruModelInfo> fetched = List.of(
                new NaruModelInfo("ollama", "llama3:8b", null),
                new NaruModelInfo("ollama", "qwen2.5-coder:7b", null),
                new NaruModelInfo("gemini", "gemini-2.5-pro", null));

        p.hideProvider("ollama", true);
        Assertions.assertEquals(List.of("gemini/gemini-2.5-pro"), keys(p.visible(fetched)),
                "the chooser must not list what this project hid");

        p.hideProvider("ollama", false);
        p.hideModel("ollama", "llama3:8b", true);
        Assertions.assertEquals(List.of("ollama/qwen2.5-coder:7b", "gemini/gemini-2.5-pro"),
                keys(p.visible(fetched)), "and one hidden model takes only itself out of the list");
    }

    private static List<String> keys(List<NaruModelInfo> infos) {
        return infos.stream().map(i -> i.provider() + "/" + i.model()).toList();
    }

    @Test
    void clearForgetsTheWholeProjectSlot() {
        ModelViewPreferences p = forNewProject();
        p.hideProvider("mistral", true);
        p.setFilter("sort", "Context size");

        p.clear();
        ModelViewPreferences after = ModelViewPreferences.of(project);
        Assertions.assertTrue(after.hiddenProviders().isEmpty());
        Assertions.assertNull(after.filter("sort"));
    }
}
