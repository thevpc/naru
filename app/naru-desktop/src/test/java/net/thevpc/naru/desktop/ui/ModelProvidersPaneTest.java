package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruModelRegistration;
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
 * exactly one write naming the right scope and the right parameter — not that the engine accepted
 * the endpoint, which the store's own tests cover.
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
        JTable table = onEdtReturn(() -> first(pane, JTable.class, c -> true));
        List<List<String>> drawn = onEdtReturn(() -> {
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

    @Test
    void selectingARowShowsItsFieldsWithTheCredentialMasked() throws Exception {
        selectRow(1);
        JLabel detail = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).contains("apiKey")));
        Assertions.assertNotNull(detail, "the selected registration lists its own parameters");
        Assertions.assertTrue(detail.getText().contains("sk-***1234"),
                "a literal key is never echoed back in full: " + detail.getText());
        Assertions.assertTrue(detail.getText().contains("gemini-2.5-pro[user]"),
                "and each field says which file it came from: " + detail.getText());
        JTextField id = onEdtReturn(() -> fieldNamed("Registration id"));
        Assertions.assertEquals("shared", id.getText());
    }

    // ── the writes ────────────────────────────────────────────────────────────

    @Test
    void saveSendsOneWriteNamingTheChosenScope() throws Exception {
        onEdt(() -> {
            fieldNamed("Registration id").setText("fast");
            fieldNamed("Parameter to write").setText("temperature");
            valueBox().setSelectedItem("0.2");
            user().setSelected(true);
            button("✓").doClick();
        });
        Assertions.assertEquals(List.of("save user update fast {temperature=0.2}"), gateway.writes,
                "one write, in the scope the radio names, carrying only the field typed");
        Assertions.assertEquals("0.2", String.valueOf(gateway.store("fast").params.get("temperature")));
    }

    @Test
    void clearingAFieldSendsANullEditSoTheRulesCanRefuseItForIdentityParams() throws Exception {
        selectRow(0);
        onEdt(() -> {
            fieldNamed("Parameter to write").setText("provider");
            button("⌫").doClick();
        });
        Assertions.assertEquals(List.of("save project update fast {provider=null}"), gateway.writes,
                "a clear is a null value, not an empty one");
        JLabel refused = onEdtReturn(() -> first(pane, JLabel.class,
                l -> String.valueOf(l.getText()).startsWith("not saved:")));
        Assertions.assertNotNull(refused, "the form reports the refusal instead of claiming a clear");
        Assertions.assertNotNull(gateway.store("fast").params.get("provider"),
                "and stripping the identity of a live endpoint is not applied");
    }

    @Test
    void thePowerGlyphFlipsEnabledAndTheTableSeesIt() throws Exception {
        selectRow(2);
        onEdt(() -> button("⏻").doClick());
        Assertions.assertEquals(List.of("save project update slow {enabled=true}"), gateway.writes);
        Assertions.assertEquals("true", String.valueOf(gateway.store("slow").params.get("enabled")));

        JTable table = onEdtReturn(() -> first(pane, JTable.class, c -> true));
        Assertions.assertEquals("yes", onEdtReturn(() -> String.valueOf(table.getValueAt(2, 4))),
                "the row redraws from the store rather than keeping the old answer");

        onEdt(() -> button("⏻").doClick());
        Assertions.assertEquals("false", String.valueOf(gateway.store("slow").params.get("enabled")),
                "and flipping again disables it");
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

    @Test
    void theValueFieldOffersTheProviderTypesForProviderAndNothingElse() throws Exception {
        onEdt(() -> fieldNamed("Parameter to write").setText("provider"));
        JComboBox<String> values = onEdtReturn(this::valueBox);
        Assertions.assertEquals(List.of("gemini", "llamacpp", "ollama"), items(values),
                "a provider type is an id, and guessing one registers nothing");

        onEdt(() -> fieldNamed("Parameter to write").setText("url"));
        Assertions.assertEquals(List.of(), items(onEdtReturn(this::valueBox)),
                "a free-text parameter is not offered a list it cannot know");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void selectRow(int index) throws Exception {
        onEdt(() -> first(pane, JTable.class, c -> true).setRowSelectionInterval(index, index));
    }

    private JTextField fieldNamed(String tooltipFragment) {
        return first(pane, JTextField.class,
                f -> String.valueOf(f.getToolTipText()).contains(tooltipFragment));
    }

    private JComboBox<String> valueBox() {
        return first(pane, JComboBox.class, c -> c.isEditable());
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
            store.forEach((id, r) -> out.put(id, NaruModelRegistration.of(id, r.params)));
            return out;
        }

        @Override
        public NaruRegistrationOrigin origin(String id) {
            Map<String, NaruRegistrationScope> scopes = new LinkedHashMap<>();
            for (String f : projectFields.getOrDefault(id, List.of())) {
                scopes.put(f, NaruRegistrationScope.PROJECT);
            }
            for (String f : userFields.getOrDefault(id, List.of())) {
                scopes.putIfAbsent(f, NaruRegistrationScope.USER);
            }
            return scopes.isEmpty() ? null : new NaruRegistrationOrigin(id, scopes);
        }

        @Override
        public String save(String id, Map<String, NElement> edits, boolean update,
                           NaruRegistrationScope scope) {
            writes.add("save " + scope.word() + " " + (update ? "update" : "create") + " " + id
                    + " " + edits);
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
            return Set.of("openai");
        }
    }
}
