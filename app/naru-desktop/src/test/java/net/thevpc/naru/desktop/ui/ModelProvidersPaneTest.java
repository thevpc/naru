package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.model.NaruModelRegistration;
import net.thevpc.naru.api.model.NaruRegistrationFields;
import net.thevpc.naru.api.model.NaruRegistrationOrigin;
import net.thevpc.naru.api.model.NaruRegistrationScope;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The registrations form as a thing a reader clicks.
 *
 * <p>Written against {@link ModelRegistrationGateway} rather than a live session, for the reason
 * {@link PlanPaneTest} gives for its service: what is worth pinning here is that a click turns into
 * exactly one write naming the right scope and the right parameters — not that the engine accepted
 * the endpoint, which the store's own tests cover.
 *
 * <p>The form is generated from {@link NaruRegistrationFields}, so these tests read it the same way:
 * by canonical field name. A widget looked up by position would pass after the vocabulary grew and
 * the reader would still be typing into a different parameter.
 */
class ModelProvidersPaneTest {

    private NPath project;
    private FakeGateway gateway;
    private ModelProvidersPane pane;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a toolkit to build widgets");
        project = NPath.ofTempFolder("naru-model-pane-" + System.nanoTime()).resolve("project");
        gateway = new FakeGateway();
        gateway.put("fast", NaruRegistrationScope.PROJECT, Map.of(
                "provider", NElement.of("ollama"),
                "model", NElement.of("llama3:8b")));
        gateway.put("shared", NaruRegistrationScope.USER, Map.of(
                "provider", NElement.of("gemini"),
                "model", NElement.of("gemini-2.5-pro")));
        // the project overrides only the key of a personal registration
        gateway.fields("shared", Map.of("shared", List.of("apiKey")),
                Map.of("shared", List.of("provider", "model")));
        gateway.store("shared").params.put("apiKey", NElement.of("sk-personal-1234"));
        gateway.put("slow", NaruRegistrationScope.PROJECT, Map.of(
                "provider", NElement.of("llamacpp"),
                "enabled", NElement.of("false")));
        gateway.models.put("ollama", List.of("qwen2.5-coder:7b", "llama3:8b"));
        onEdt(() -> {
            pane = new ModelProvidersPane(() -> project, gateway);
            pane.setSize(720, 520);
            pane.doLayout();
        });
    }

    @AfterEach
    void tearDown() {
        ModelViewPreferences.of(project).clear();
    }

    // ── the listing ───────────────────────────────────────────────────────────

    @Test
    void oneRowPerRegistrationNamesItsEndpointAndTheLayerThatOwnsIt() throws Exception {
        List<List<String>> drawn = onEdtReturn(() -> {
            JTable table = list();
            List<List<String>> out = new ArrayList<>();
            for (int r = 0; r < table.getRowCount(); r++) {
                List<String> row = new ArrayList<>();
                for (int c = 0; c < table.getColumnCount(); c++) {
                    row.add(String.valueOf(table.getValueAt(r, c)));
                }
                out.add(row);
            }
            return out;
        });
        Assertions.assertEquals(List.of("fast", "shared", "slow"),
                drawn.stream().map(r -> r.get(0)).toList(), "sorted by id, every registration in view");
        Assertions.assertEquals("project", drawn.get(0).get(3));
        Assertions.assertEquals("project over user", drawn.get(1).get(3),
                "an override and a registration of the same id must not look alike");
        Assertions.assertEquals("no", drawn.get(2).get(4), "the disabled flag is read from the store");
    }

    // ── the form the vocabulary generates ─────────────────────────────────────

    @Test
    void everyDeclaredFieldHasItsOwnInputInTheVocabularySOrder() throws Exception {
        List<String> keys = NaruRegistrationFields.keys();
        Assertions.assertEquals(NaruRegistrationFields.fields().size(), keys.size(),
                "the form has one row per declared field, so the two counts must be the same thing");
        for (String key : keys) {
            Assertions.assertNotNull(pane.fieldInput(key), "'" + key + "' has no input of its own");
        }
        Map<String, String> widgets = onEdtReturn(() -> {
            Map<String, String> out = new LinkedHashMap<>();
            for (NaruRegistrationFields.Field<?> f : NaruRegistrationFields.fields()) {
                JComponent w = pane.fieldInput(f.key());
                out.put(f.key(), w instanceof JComboBox<?> ? "choice"
                        : w instanceof JTextField ? "text" : w.getClass().getSimpleName());
            }
            return out;
        });
        Assertions.assertEquals("choice", widgets.get("provider"), "a provider type is picked or typed");
        Assertions.assertEquals("choice", widgets.get("protocol"));
        Assertions.assertEquals("choice", widgets.get("model"), "a model is picked or typed");
        Assertions.assertEquals("MultiValueField", widgets.get("models"),
                "a model list is free text with a multi-select picker");
        Assertions.assertEquals("choice", widgets.get("contextLength"),
                "a context length offers the common sizes and still takes an exact number");
        Assertions.assertEquals("choice", widgets.get("enabled"),
                "a flag is true or false, with blank meaning inherited, not free text");
        Assertions.assertEquals("text", widgets.get("url"), "an endpoint is not a closed set");
        Assertions.assertEquals("text", widgets.get("temperature"));
    }

    @Test
    void selectingARowFillsEveryFieldAndNeverEchoesTheLiteralKey() throws Exception {
        selectRow(1);
        Assertions.assertEquals("shared", onEdtReturn(() -> idField().getText()));
        Assertions.assertEquals("gemini", onEdtReturn(() -> pane.fieldText("provider")));
        Assertions.assertEquals("gemini-2.5-pro", onEdtReturn(() -> pane.fieldText("model")));
        String key = onEdtReturn(() -> pane.fieldText("apiKey"));
        Assertions.assertEquals("sk-***1234", key,
                "the credential a registration holds is a literal, so the form shows its mask");
        Assertions.assertFalse(key.contains("personal"), "and never the value itself");
        Assertions.assertEquals("user", onEdtReturn(() -> pane.fieldBadge("model").getText()),
                "each field says which file it came from");
        Assertions.assertEquals("project", onEdtReturn(() -> pane.fieldBadge("apiKey").getText()));
    }

    @Test
    void anAliasSpelledKeyReadsIntoTheFieldItFoldsOnto() throws Exception {
        // a file that says baseUrl has one meaning, and the form's url field is where it shows up
        Map<String, NElement> local = new LinkedHashMap<>();
        local.put("provider", NElement.of("llamacpp"));
        local.put("baseUrl", NElement.of("http://127.0.0.1:8080"));
        gateway.put("aliasy", NaruRegistrationScope.PROJECT, local);
        refresh();
        selectId("aliasy");
        Assertions.assertEquals("aliasy", onEdtReturn(() -> idField().getText()));
        Assertions.assertEquals("http://127.0.0.1:8080", onEdtReturn(() -> pane.fieldText("url")),
                "the alias is folded at the vocabulary, so there is no second row for it");
        Assertions.assertEquals("project", onEdtReturn(() -> pane.fieldBadge("url").getText()));
        Assertions.assertEquals("", onEdtReturn(() -> pane.fieldText("apiKey")),
                "a parameter the file does not carry stays empty, not invented");
    }

    @Test
    void theProviderFieldOffersTheTypesTheSessionKnowsAndChoosingOneRefillsTheProtocols()
            throws Exception {
        selectRow(0);
        JComboBox<String> providers = onEdtReturn(() -> (JComboBox<String>) pane.fieldInput("provider"));
        Assertions.assertEquals(List.of("gemini", "llamacpp", "ollama"), items(providers),
                "a provider type is an id, and guessing one registers nothing");
        Assertions.assertEquals(List.of("anthropic", "gemini", "mistral", "ollama", "openai", "openrouter"),
                items(onEdtReturn(() -> (JComboBox<String>) pane.fieldInput("protocol"))),
                "the wire list starts from the common protocols plus the endpoint being edited");

        onEdt(() -> setInput("provider", "llamacpp"));
        Assertions.assertEquals(List.of("anthropic", "gemini", "llama-cpp", "mistral", "ollama",
                        "openai", "openrouter"),
                items(onEdtReturn(() -> (JComboBox<String>) pane.fieldInput("protocol"))),
                "and follows the provider chosen");
        Assertions.assertTrue(onEdtReturn(() -> pane.fieldInput("url") instanceof JTextField),
                "a free-text parameter is not offered a list it cannot know");
    }

    @Test
    void theModelFieldOffersTheModelsTheProviderReportsAndStillTakesFreeText() throws Exception {
        selectRow(0);
        JComboBox<String> model = onEdtReturn(() -> (JComboBox<String>) pane.fieldInput("model"));
        Assertions.assertEquals(List.of("llama3:8b", "qwen2.5-coder:7b"), items(model),
                "the provider's own listing is what the picker offers");

        onEdt(() -> setInput("model", "my-private:latest"));
        onEdt(() -> button("✓").doClick());
        Assertions.assertEquals(List.of("save project update fast model=my-private:latest"), gateway.writes,
                "a model the endpoint does not report is still a legal pin");
    }

    @Test
    void theModelsFieldIsFreeTextWithAMultiSelectPicker() throws Exception {
        selectRow(0);
        MultiValueField models = onEdtReturn(() -> (MultiValueField) pane.fieldInput("models"));
        Assertions.assertEquals(List.of("llama3:8b", "qwen2.5-coder:7b"), models.candidates(),
                "the picker lists every model the endpoint reports");

        onEdt(() -> {
            models.setText("my-private:latest");
            models.applySelection(List.of("llama3:8b"));
        });
        Assertions.assertEquals("my-private:latest, llama3:8b", models.getText(),
                "picking a reported model must not erase a typed one");

        onEdt(() -> button("✓").doClick());
        Assertions.assertEquals(List.of("save project update fast models=my-private:latest, llama3:8b"),
                gateway.writes, "both selected and typed models travel as one list");
    }

    @Test
    void aFlagOffersBlankTrueAndFalseAndBlankClearsTheKey() throws Exception {
        selectRow(2);
        JComboBox<String> enabled = onEdtReturn(() -> (JComboBox<String>) pane.fieldInput("enabled"));
        Assertions.assertEquals(List.of("", "true", "false"), items(enabled),
                "the blank entry is the inherited state, not a flag value");

        onEdt(() -> setInput("enabled", ""));
        onEdt(() -> button("✓").doClick());
        Assertions.assertEquals(List.of("save project update slow enabled=null"), gateway.writes,
                "blank is a null edit, so the provider's default applies again");
        Assertions.assertNull(gateway.store("slow").params.get("enabled"));

        onEdt(() -> setInput("enabled", "inherit"));
        onEdt(() -> button("✓").doClick());
        Assertions.assertEquals(List.of("save project update slow enabled=null"), gateway.writes,
                "'inherit' is a state, not a flag: it must not be filed as if it were one");
        JLabel problem = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).contains("wants true or false")));
        Assertions.assertNotNull(problem, "and the reader is told a flag is true or false");
    }

    @Test
    void contextLengthOffersCommonSizesAndKeepsAnExactValue() throws Exception {
        selectRow(0);
        JComboBox<String> context = onEdtReturn(
                () -> (JComboBox<String>) pane.fieldInput("contextLength"));
        Assertions.assertTrue(items(context).containsAll(List.of("4096", "8192", "32768", "131072")),
                "the common context sizes are the picker");

        onEdt(() -> setInput("contextLength", "12345"));
        onEdt(() -> button("✓").doClick());
        Assertions.assertEquals(List.of("save project update fast contextLength=12345"), gateway.writes,
                "an exact length the list does not hold is still accepted");
    }

    // ── the writes ────────────────────────────────────────────────────────────

    @Test
    void oneClickProducesExactlyOneSaveNamingTheChosenScope() throws Exception {
        selectRow(0);
        onEdt(() -> {
            setInput("temperature", "0.2");
            user().setSelected(true);
            button("✓").doClick();
        });
        Assertions.assertEquals(List.of("save user update fast temperature=0.2"), gateway.writes,
                "one click, one write, in the scope the radio names, carrying only the field changed");
        Assertions.assertEquals("0.2", String.valueOf(gateway.store("fast").params.get("temperature")));
    }

    @Test
    void oneClickWritesEveryChangedFieldInThatOneSave() throws Exception {
        selectRow(0);
        onEdt(() -> {
            setInput("contextLength", "8192");
            setInput("stop", "</s>");
            button("✓").doClick();
        });
        Assertions.assertEquals(List.of("save project update fast contextLength=8192, stop=</s>"),
                gateway.writes, "two fields, one write, in the file order the vocabulary declares");
    }

    @Test
    void aChangedFieldNamesTheFileItsWriteWillLandIn() throws Exception {
        selectRow(0);
        onEdt(() -> {
            setInput("maxTokens", "4096");
            // clicked, not setSelected: moving the destination is a user action, and only the click
            // is what the pane listens to
            user().doClick();
        });
        Assertions.assertEquals("-> user", onEdtReturn(() -> pane.fieldBadge("maxTokens").getText()),
                "the badge moves from where the value lives to where the write goes");
        Assertions.assertEquals("project", onEdtReturn(() -> pane.fieldBadge("provider").getText()),
                "and stays on the file it came from when the field was not touched");
    }

    @Test
    void clearingAFieldSendsANullEditSoTheRulesCanRefuseItForIdentityParams() throws Exception {
        selectRow(0);
        onEdt(() -> {
            setInput("provider", "");
            button("✓").doClick();
        });
        Assertions.assertEquals(List.of("save project update fast provider=null"), gateway.writes,
                "a clear is a null value, not an empty one");
        JLabel refused = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).startsWith("not saved:")));
        Assertions.assertNotNull(refused, "the form reports the refusal instead of claiming a clear");
        Assertions.assertNotNull(gateway.store("fast").params.get("provider"),
                "and stripping the identity of a live endpoint is not applied");
    }

    @Test
    void aFieldThatWantsANumberRefusesTextInsteadOfFilingItAway() throws Exception {
        selectRow(0);
        onEdt(() -> {
            setInput("temperature", "warm");
            button("✓").doClick();
        });
        Assertions.assertEquals(List.of(), gateway.writes,
                "'warm' stored as a string would read back as unset — the silent kind of lost setting");
        JLabel problem = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).contains("wants a number")));
        Assertions.assertNotNull(problem, "and the reader is told which field and why");
    }

    @Test
    void thePowerGlyphFlipsEnabledAndTheTableSeesIt() throws Exception {
        selectRow(2);
        onEdt(() -> button("⏻").doClick());
        Assertions.assertEquals(List.of("save project update slow enabled=true"), gateway.writes);
        Assertions.assertEquals("true", String.valueOf(gateway.store("slow").params.get("enabled")));
        Assertions.assertEquals("true", onEdtReturn(() -> pane.fieldText("enabled")),
                "the form refills from the store rather than keeping the old answer");

        JTable table = onEdtReturn(this::list);
        Assertions.assertEquals("yes", onEdtReturn(() -> String.valueOf(table.getValueAt(2, 4))));

        onEdt(() -> button("⏻").doClick());
        Assertions.assertEquals("false", String.valueOf(gateway.store("slow").params.get("enabled")));
        Assertions.assertEquals("false", onEdtReturn(() -> pane.fieldText("enabled")));
    }

    @Test
    void aDeleteOnlyPromisesWhatTheChosenScopeCanActuallyDo() throws Exception {
        selectRow(0);
        // 'fast' is the project's own; the user scope is selected, so there is nothing there to delete
        onEdt(() -> {
            user().setSelected(true);
            button("×").doClick();
        });
        Assertions.assertEquals(List.of(), gateway.writes,
                "deleting where it is not declared would silently delete nothing");
        JLabel status = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).contains("do not declare")));
        Assertions.assertNotNull(status, "and the reader is told which scope holds it");
    }

    @Test
    void withoutASessionTheFormSaysSoAndCannotWrite() throws Exception {
        gateway.available = false;
        onEdt(() -> button("⟳").doClick());
        for (String glyph : List.of("✓", "⌫", "⏻", "×")) {
            JButton b = onEdtReturn(() -> button(glyph));
            Assertions.assertFalse(b.isEnabled(), "'" + glyph + "' promises a write there is no session for");
        }
        JLabel notice = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).contains("Open a session")));
        Assertions.assertNotNull(notice, "empty is explained, not left to be guessed");
    }

    // ── the undeclared parameters ─────────────────────────────────────────────

    @Test
    void undeclaredParametersGetTheirOwnRowsAndACellCommitIsOneWrite() throws Exception {
        Map<String, NElement> local = new LinkedHashMap<>();
        local.put("provider", NElement.of("llamacpp"));
        local.put("bin", NElement.of("/usr/bin/llama-server"));
        local.put("n_ctx", NElement.of(4096L));
        gateway.put("local", NaruRegistrationScope.PROJECT, local);
        refresh();
        selectId("local");
        Assertions.assertEquals("local", onEdtReturn(() -> idField().getText()));

        List<List<String>> shown = onEdtReturn(() -> {
            JTable extras = extras();
            List<List<String>> out = new ArrayList<>();
            for (int r = 0; r < extras.getRowCount(); r++) {
                List<String> row = new ArrayList<>();
                for (int c = 0; c < extras.getColumnCount(); c++) {
                    row.add(String.valueOf(extras.getValueAt(r, c)));
                }
                out.add(row);
            }
            return out;
        });
        Assertions.assertEquals(List.of("bin", "n_ctx", ""),
                shown.stream().map(r -> r.get(0)).toList(),
                "the stored knobs, then one blank row that adds another");
        Assertions.assertEquals(List.of("/usr/bin/llama-server", "4096", ""),
                shown.stream().map(r -> r.get(1)).toList());
        Assertions.assertEquals("project", shown.get(0).get(2));

        onEdt(() -> extras().setValueAt("/opt/llama-server", 0, 1));
        Assertions.assertEquals(List.of("save project update local bin=/opt/llama-server"), gateway.writes,
                "one cell, one write");

        gateway.writes.clear();
        onEdt(() -> {
            extras().setValueAt("threads", 2, 0);
            extras().setValueAt("8", 2, 1);
        });
        Assertions.assertEquals(List.of("save project update local threads=8"), gateway.writes,
                "an undeclared key is added by naming it, not by guessing a field core owns");

        gateway.writes.clear();
        onEdt(() -> {
            extras().setRowSelectionInterval(0, 0);
            button("⌫").doClick();
        });
        Assertions.assertEquals(List.of("save project update local bin=null"), gateway.writes);
        Assertions.assertNull(gateway.store("local").params.get("bin"));
    }

    // ── the view axis ─────────────────────────────────────────────────────────

    @Test
    void hidingAProviderIsAWindowSettingAndNeverAWriteToTheStore() throws Exception {
        onEdt(() -> {
            fieldNamed("A provider name").setText("mistral");
            button("＋").doClick();
        });
        Assertions.assertEquals(List.of(), gateway.writes,
                "hiding what I see must not touch what the project is allowed to run");
        Assertions.assertEquals(Set.of("mistral"), ModelViewPreferences.of(project).hiddenProviders());
        JLabel hidden = onEdtReturn(() -> first(pane, JLabel.class, l -> "mistral".equals(l.getText())));
        Assertions.assertNull(hidden, "the hidden entries live in their own list, not among the labels");

        onEdt(() -> {
            javax.swing.JList<String> list = first(pane, javax.swing.JList.class, c -> true);
            list.setSelectedIndex(0);
            button("－").doClick();
        });
        Assertions.assertTrue(ModelViewPreferences.of(project).hiddenProviders().isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void selectRow(int index) throws Exception {
        onEdt(() -> list().setRowSelectionInterval(index, index));
    }

    /** Select by id rather than index: a test that adds a registration should not count rows. */
    private void selectId(String id) throws Exception {
        int index = onEdtReturn(() -> {
            JTable table = list();
            for (int r = 0; r < table.getRowCount(); r++) {
                if (id.equals(String.valueOf(table.getValueAt(r, 0)))) {
                    return r;
                }
            }
            return -1;
        });
        Assertions.assertTrue(index >= 0, "'" + id + "' is not in the listing");
        selectRow(index);
    }

    /** Pull the store back off the gateway, the way the ⟳ button does. */
    private void refresh() throws Exception {
        onEdt(() -> button("⟳").doClick());
    }

    private void setInput(String key, String value) {
        JComponent w = pane.fieldInput(key);
        if (w instanceof JComboBox<?> box) {
            box.getEditor().setItem(value);
        } else {
            ((JTextField) w).setText(value);
        }
    }

    private JTable list() {
        return named("naru.registration.list");
    }

    private JTable extras() {
        return named("naru.registration.extras");
    }

    private JTable named(String name) {
        return first(pane, JTable.class, t -> name.equals(t.getName()));
    }

    private JTextField idField() {
        return first(pane, JTextField.class,
                f -> String.valueOf(f.getToolTipText()).contains("Registration id"));
    }

    private JTextField fieldNamed(String tooltipFragment) {
        return first(pane, JTextField.class,
                f -> String.valueOf(f.getToolTipText()).contains(tooltipFragment));
    }

    private JRadioButton user() {
        return first(pane, JRadioButton.class, r -> "User".equals(r.getText()));
    }

    private JButton button(String glyph) {
        return first(pane, JButton.class, b -> glyph.equals(b.getText()));
    }

    private static List<String> items(JComboBox<String> box) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            out.add(box.getItemAt(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T first(Container root, Class<T> type,
                                                 java.util.function.Predicate<T> match) {
        List<T> found = new ArrayList<>();
        collect(root, type, match, found);
        return found.isEmpty() ? null : (T) found.get(0);
    }

    private static <T extends Component> void collect(Container root, Class<T> type,
                                                      java.util.function.Predicate<T> match,
                                                      List<T> out) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && match.test(type.cast(c))) {
                out.add(type.cast(c));
            }
            if (c instanceof Container container) {
                collect(container, type, match, out);
            }
        }
    }

    private void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    private <T> T onEdtReturn(java.util.concurrent.Callable<T> body) throws Exception {
        Object[] box = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                box[0] = body.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return (T) box[0];
    }

    /**
     * The store the pane talks to. It applies an edit so a reload shows what a real one would, and
     * records every write as one line the assertions can name exactly.
     */
    private static final class FakeGateway implements ModelRegistrationGateway {

        final Map<String, FakeRegistration> store = new TreeMap<>();
        final Map<String, List<String>> projectFields = new LinkedHashMap<>();
        final Map<String, List<String>> userFields = new LinkedHashMap<>();
        final Map<String, List<String>> models = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean available = true;

        static final class FakeRegistration {
            final String id;
            final Map<String, NElement> params = new LinkedHashMap<>();

            FakeRegistration(String id) {
                this.id = id;
            }
        }

        void put(String id, NaruRegistrationScope scope, Map<String, NElement> params) {
            FakeRegistration r = store.computeIfAbsent(id, FakeRegistration::new);
            r.params.putAll(params);
            Map<String, List<String>> names =
                    scope == NaruRegistrationScope.PROJECT ? projectFields : userFields;
            names.put(id, new ArrayList<>(params.keySet()));
        }

        void fields(String id, Map<String, List<String>> project, Map<String, List<String>> user) {
            projectFields.put(id, new ArrayList<>(project.get(id)));
            userFields.put(id, new ArrayList<>(user.get(id)));
        }

        FakeRegistration store(String id) {
            return store.get(id);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Map<String, NaruModelRegistration> registrations() {
            Map<String, NaruModelRegistration> out = new TreeMap<>();
            store.forEach((id, r) -> {
                NaruModelRegistration.Builder b = NaruModelRegistration.builder(id);
                r.params.forEach(b::put);
                out.put(id, b.build());
            });
            return out;
        }

        @Override
        public NaruRegistrationOrigin origin(String id) {
            Map<String, NaruRegistrationScope> scopes = new LinkedHashMap<>();
            // a real origin names canonical parameters even when the file spelled them as an alias,
            // because that is the only way an editor can find the owner of the field it shows
            java.util.function.Function<String, String> canonical = name -> {
                NaruRegistrationFields.Field<?> f = NaruRegistrationFields.find(name);
                return f == null ? name : f.key();
            };
            projectFields.getOrDefault(id, List.of()).forEach(f -> scopes.put(canonical.apply(f),
                    NaruRegistrationScope.PROJECT));
            userFields.getOrDefault(id, List.of()).forEach(f -> scopes.putIfAbsent(canonical.apply(f),
                    NaruRegistrationScope.USER));
            return scopes.isEmpty() ? null : new NaruRegistrationOrigin(id, scopes);
        }

        @Override
        public String save(String id, Map<String, NElement> edits, boolean update,
                           NaruRegistrationScope scope) {
            writes.add("save " + scope.word() + " " + (update ? "update" : "create") + " " + id + " "
                    + describe(edits));
            FakeRegistration r = store.computeIfAbsent(id, FakeRegistration::new);
            Map<String, NElement> params = new LinkedHashMap<>(r.params);
            Map<String, List<String>> owned =
                    scope == NaruRegistrationScope.PROJECT ? projectFields : userFields;
            List<String> names = new ArrayList<>(owned.getOrDefault(id, List.of()));
            edits.forEach((k, v) -> {
                if (v == null || v.isNull()) {
                    params.remove(k);
                    names.remove(k);
                } else {
                    params.put(k, v);
                    if (!names.contains(k)) {
                        names.add(k);
                    }
                }
            });
            // the real rules refuse this write, and a fake that accepted it would let the pane prove
            // something no session ever does: an endpoint with neither a provider nor a wire
            String missing = identityOf(id, params);
            if (missing != null) {
                return missing;
            }
            r.params.clear();
            r.params.putAll(params);
            owned.put(id, names);
            return null;
        }

        /** One line per write: the edits are printed by value, so an assertion can name what was sent. */
        private static String describe(Map<String, NElement> edits) {
            List<String> parts = new ArrayList<>();
            edits.forEach((k, v) -> parts.add(k + "=" + (v == null ? "null" : NaruTaskConfig.toText(v))));
            return String.join(", ", parts);
        }

        private static String identityOf(String id, Map<String, NElement> params) {
            if (params.get("provider") != null || params.get("protocol") != null) {
                return null;
            }
            return "cannot clear '" + id + "'s provider (an update only merges) — a registration is a "
                    + "provider instance or a generic endpoint, it cannot carry neither";
        }

        @Override
        public String remove(String id, NaruRegistrationScope scope) {
            writes.add("remove " + scope.word() + " " + id);
            return null;
        }

        @Override
        public List<String> providerTypes() {
            return List.of("ollama", "llamacpp", "gemini");
        }

        @Override
        public Set<String> protocols(String providerType) {
            return switch (providerType == null ? "" : providerType) {
                case "llamacpp" -> Set.of("openai", "llama-cpp");
                case "gemini" -> Set.of("gemini");
                default -> Set.of("openai");
            };
        }

        @Override
        public List<String> models(String registrationId, String providerType) {
            return models.getOrDefault(providerType, List.of());
        }
    }
}
