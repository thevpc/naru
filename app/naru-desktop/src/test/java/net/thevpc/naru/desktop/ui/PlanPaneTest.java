package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The board as a thing a reader clicks.
 *
 * <p>The panel is handed a {@link NaruPlanService} instead of a live session: the plan extension is
 * not on this module's test classpath, and what is worth pinning here is that a click becomes exactly
 * one service call — not that the extension accepts it, which the extension's own tests cover.
 */
class PlanPaneTest {

    private NaruSessionModel model;
    private InteractionHost host;
    private List<NaruPlanSnapshot> board;
    private List<String> writes;
    private PlanPane pane;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a toolkit to build widgets");
        NPath dir = NPath.ofTempFolder("naru-desktop-plan-pane");
        board = new ArrayList<>(List.of(
                new NaruPlanSnapshot("active-plan", "ship the cockpit", NaruPlanState.OPEN,
                        List.of(item("item-a", "write the rail", NaruPlanItemState.READY),
                                item("item-b", "polish the header", NaruPlanItemState.RUNNING),
                                item("item-c", "test it", NaruPlanItemState.PENDING)), true),
                new NaruPlanSnapshot("dormant-plan", "an older idea", NaruPlanState.PENDING,
                        List.of(item("item-d", "something else", NaruPlanItemState.PENDING)), false)));
        writes = new ArrayList<>();
        NaruPlanService service = new RecordingPlanService();
        onEdt(() -> {
            host = new InteractionHost();
            model = new NaruSessionModel(() -> null, dir, host);
            // Runnable::run keeps the test deterministic: a click reaches the service before the
            // assertion, while production writes use a background worker
            pane = new PlanPane(model, () -> service, Runnable::run);
            pane.setSize(340, 520);
            pane.doLayout();
        });
        awaitBoard();
    }

    @AfterEach
    void tearDown() throws Exception {
        // no flush timer is started in this fixture, so the host needs no shutdown of its own
        onEdt(() -> {
            if (pane != null) {
                pane.dispose();
            }
        });
    }

    private static NaruPlanItemSnapshot item(String id, String description, NaruPlanItemState state) {
        return new NaruPlanItemSnapshot(id, id, description, state, List.of(), null, null, 0);
    }

    /** Drive the debounced read the way a session change would, then wait for the board to appear. */
    private void awaitBoard() throws Exception {
        onEdt(pane::sessionModelChanged);
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(60);
            if (Boolean.TRUE.equals(onEdtReturn(() -> findButton(pane, "⏸") != null))) {
                return;
            }
        }
        assertTrue(false, "the plan board never drew; writes were " + writes);
    }

    // ── which plan is pointed at ──────────────────────────────────────────────

    @Test
    void everyPlanIsEditableAndTheActiveOneOffersDeactivation() throws Exception {
        assertNotNull(onEdtReturn(() -> findButton(pane, "⏸")), "the active plan can be deactivated");
        assertNotNull(onEdtReturn(() -> findButton(pane, "▶")), "the dormant plan can be activated");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "＋")).size(),
                "both registered plans offer add-an-item, active or not");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "×")).size(),
                "only the open plan's items are drawn; the running item carries no remove control");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "🗑")).size(),
                "every registered plan can be deleted from its row");
    }

    @Test
    void thePlanListOpensTheActivePlanAndKeepsTheOthersClosed() throws Exception {
        assertNotNull(onEdtReturn(() -> findLabel(pane, "write the rail")),
                "the active plan is open on the board");
        assertNull(onEdtReturn(() -> findLabel(pane, "something else")),
                "a dormant plan's items stay folded until its row is opened");
        assertNotNull(onEdtReturn(() -> findLabelContaining(pane, "Registered plans · 2")),
                "the panel lists every registered plan");
    }

    @Test
    void openingADormantPlanShowsItsItemsAndClosesTheActiveOne() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "an older idea")));
        assertNotNull(onEdtReturn(() -> findLabel(pane, "something else")),
                "the opened plan's item is drawn");
        assertNull(onEdtReturn(() -> findLabel(pane, "write the rail")),
                "the previously open plan is folded, not duplicated");
    }

    @Test
    void deletingAPlanIsOneWriteNamingTheWholePlan() throws Exception {
        List<JButton> delete = onEdtReturn(() -> findButtons(pane, "🗑"));
        onEdt(() -> delete.get(0).doClick());
        assertEquals(List.of("delete active-plan"), writes);
    }

    @Test
    void anActivatedPlanInANonExecutingModeIsShownAsDormant() throws Exception {
        // the fixture has no live session, so the mode is not executing: the active plan must read
        // as chosen-but-not-running rather than live
        assertNotNull(onEdtReturn(() -> findLabelContaining(pane, "active, dormant")),
                "the active plan says it is dormant until an executing mode");
    }

    @Test
    void selectingADormantPlanIsOneWrite() throws Exception {
        JButton activate = onEdtReturn(() -> findButton(pane, "▶"));
        assertNotNull(activate, "a dormant plan is offered for activation");
        onEdt(() -> activate.doClick());
        assertEquals(List.of("activate dormant-plan"), writes);
    }

    @Test
    void deselectingTheActivePlanIsOneWrite() throws Exception {
        JButton pause = onEdtReturn(() -> findButton(pane, "⏸"));
        onEdt(() -> pause.doClick());
        assertEquals(List.of("deactivate"), writes);
    }

    @Test
    void removingAnItemIsOneWriteNamingItsPlan() throws Exception {
        List<JButton> remove = onEdtReturn(() -> findButtons(pane, "×"));
        onEdt(() -> remove.get(0).doClick());
        assertEquals(List.of("remove active-plan item-a"), writes);
    }

    // ── creating and editing plans ────────────────────────────────────────────

    @Test
    void theToolbarMakesANewPlanThroughTheSameDoor() throws Exception {
        JButton newPlan = onEdtReturn(() -> findButton(pane, "＋ New plan"));
        assertNotNull(newPlan, "the board always offers a new plan");
        onEdt(newPlan::doClick);
        JTextField field = onEdtReturn(() -> findField(pane));
        assertNotNull(field, "the new-plan control opens a field for the goal");
        onEdt(() -> field.setText("migrate the build"));
        submit(field);
        assertEquals(List.of("create migrate the build"), writes);
    }

    @Test
    void editingADormantPlansItemNeverTouchesTheActivePlan() throws Exception {
        // open the dormant plan first; item-d belongs to it, and the line must name that plan
        click(onEdtReturn(() -> findLabel(pane, "an older idea")));
        JLabel row = onEdtReturn(() -> findLabel(pane, "something else"));
        assertNotNull(row, "the dormant plan's item is on the board once its row is opened");
        click(row);
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("something else, reworded"));
        submit(field);
        assertEquals(List.of("edit dormant-plan item-d something else, reworded"), writes);
    }

    @Test
    void addingAnItemToAClosedPlanOpensItFirstAndNamesIt() throws Exception {
        List<JButton> add = onEdtReturn(() -> findButtons(pane, "＋"));
        // the active plan is listed first; the second control belongs to the dormant plan
        onEdt(() -> add.get(1).doClick());
        JTextField field = onEdtReturn(() -> findField(pane));
        assertNotNull(field, "add opens a field in the plan it belongs to");
        onEdt(() -> field.setText("land the docs"));
        submit(field);
        assertEquals(List.of("add dormant-plan land the docs"), writes);
    }

    // ── editing the text ──────────────────────────────────────────────────────

    @Test
    void clickOnAnItemOpensItsSentenceAndEnterSendsTheRewrite() throws Exception {
        JLabel row = onEdtReturn(() -> findLabel(pane, "write the rail"));
        assertNotNull(row, "the item is drawn as its description");
        click(row);
        JTextField field = onEdtReturn(() -> findField(pane));
        assertNotNull(field, "the click opened a field in the row");
        assertEquals("write the rail", field.getText(), "the field starts as the sentence it replaces");
        onEdt(() -> field.setText("write the rail, then test --the flag"));
        submit(field);
        assertEquals(List.of("edit active-plan item-a write the rail, then test --the flag"), writes);
    }

    @Test
    void theGoalIsRestatedThroughTheSameDoor() throws Exception {
        JLabel goal = onEdtReturn(() -> findLabel(pane, "ship the cockpit"));
        assertNotNull(goal, "the plan is drawn as its goal");
        click(goal);
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("ship the cockpit and the rail"));
        submit(field);
        assertEquals(List.of("goal active-plan ship the cockpit and the rail"), writes);
    }

    @Test
    void theAddControlOpensABlankFieldAndSendsWhatIsTypedIntoIt() throws Exception {
        JButton plus = onEdtReturn(() -> findButton(pane, "＋"));
        assertNotNull(plus, "the active plan offers add-an-item");
        onEdt(plus::doClick);
        JTextField field = onEdtReturn(() -> findField(pane));
        assertNotNull(field, "add an item starts a field, not a dialog");
        onEdt(() -> field.setText("land the docs"));
        submit(field);
        assertEquals(List.of("add active-plan land the docs"), writes);
    }

    @Test
    void aRunningItemRefusesToOpenAField() throws Exception {
        JLabel busy = onEdtReturn(() -> findLabel(pane, "polish the header"));
        assertNotNull(busy, "the running item is still on the board");
        assertEquals(0, onEdtReturn(() -> busy.getMouseListeners().length),
                "its text is the instruction a task is working from, so the row is not clickable");
        assertNull(onEdtReturn(() -> findField(pane)));
        assertEquals(List.of(), writes);
    }

    @Test
    void aRepaintWhileTheReaderTypesLeavesTheFieldAlone() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("half a sentence"));
        // a streaming turn fires the model listener constantly; none of it may swallow the draft
        onEdt(pane::sessionModelChanged);
        Thread.sleep(600);
        JTextField still = onEdtReturn(() -> findField(pane));
        assertSame(field, still, "the board must not repaint over an open editor");
        assertEquals("half a sentence", still.getText());
        assertEquals(List.of(), writes, "waiting is not sending");
    }

    @Test
    void escapeClosesTheFieldWithoutSendingAnything() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("never mind"));
        pressEscape(field);
        assertNull(onEdtReturn(() -> findField(pane)), "escape closes the editor");
        assertEquals(List.of(), writes);
        assertNotNull(onEdtReturn(() -> findLabel(pane, "write the rail")), "the row comes back");
    }

    @Test
    void anEmptySentenceSendsNothing() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("   "));
        JButton commit = onEdtReturn(() -> findButton(pane, "✓"));
        assertNotNull(commit, "the editor carries a commit button");
        onEdt(commit::doClick);
        assertEquals(List.of(), writes, "a blank description is not a plan item");
        assertNull(onEdtReturn(() -> findField(pane)), "and the editor still closes");
    }

    /** The service seam, recorded as one readable line per call instead of touching a real graph. */
    private final class RecordingPlanService implements NaruPlanService {

        @Override
        public String name() {
            return "plan";
        }

        @Override
        public List<NaruPlanSnapshot> allPlans() {
            return List.copyOf(board);
        }

        @Override
        public NaruPlanSnapshot activePlan() {
            for (NaruPlanSnapshot p : board) {
                if (p.active()) {
                    return p;
                }
            }
            return null;
        }

        @Override
        public NaruPlanSnapshot createPlan(String goal) {
            writes.add("create " + goal);
            return new NaruPlanSnapshot("created-plan", goal, NaruPlanState.PENDING, List.of(), false);
        }

        @Override
        public NaruPlanSnapshot setGoal(String planId, String goal) {
            writes.add("goal " + planId + " " + goal);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot addItem(String planId, String description) {
            writes.add("add " + planId + " " + description);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot editItem(String planId, String itemId, String description) {
            writes.add("edit " + planId + " " + itemId + " " + description);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot removeItem(String planId, String itemId) {
            writes.add("remove " + planId + " " + itemId);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot activate(String planId) {
            writes.add("activate " + planId);
            return board.get(0);
        }

        @Override
        public void deactivate() {
            writes.add("deactivate");
        }

        @Override
        public void deletePlan(String planId) {
            writes.add("delete " + planId);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    private <T> T onEdtReturn(Callable<T> body) throws Exception {
        T[] box = (T[]) new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                box[0] = body.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return box[0];
    }

    /** Send the click that turns a label into a field. */
    private void click(Component label) throws Exception {
        assertNotNull(label, "there is a row to click");
        onEdt(() -> {
            MouseListener[] listeners = ((JLabel) label).getMouseListeners();
            assertTrue(listeners.length > 0, "the text is not clickable: " + label);
            MouseEvent click = new MouseEvent(label, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false);
            for (MouseListener l : listeners) {
                l.mouseClicked(click);
            }
        });
    }

    private void submit(JTextField field) throws Exception {
        onEdt(() -> {
            assertTrue(field.getActionListeners().length > 0, "Enter is wired");
            for (var a : field.getActionListeners()) {
                a.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, "submit"));
            }
        });
    }

    /**
     * Hand the key press to the field's own listeners. A component that is not in a shown window
     * never becomes the focused one, so {@code dispatchEvent} would be dropped by the
     * KeyboardFocusManager before the editor's handler saw it.
     */
    private void pressEscape(JTextField field) throws Exception {
        onEdt(() -> {
            KeyEvent escape = new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            assertTrue(field.getKeyListeners().length > 0, "escape is wired");
            for (var l : field.getKeyListeners()) {
                l.keyPressed(escape);
            }
        });
    }

    private static JButton findButton(Container root, String glyph) {
        List<JButton> found = findButtons(root, glyph);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<JButton> findButtons(Container root, String glyph) {
        List<JButton> out = new ArrayList<>();
        collect(root, JButton.class, b -> glyph.equals(b.getText()), out);
        return out;
    }

    private static JLabel findLabel(Container root, String text) {
        List<JLabel> out = new ArrayList<>();
        collect(root, JLabel.class, l -> text.equals(l.getText()), out);
        return out.isEmpty() ? null : out.get(0);
    }

    /** For a label whose exact words the design may still be tuning, e.g. the dormant badge. */
    private static JLabel findLabelContaining(Container root, String fragment) {
        List<JLabel> out = new ArrayList<>();
        collect(root, JLabel.class, l -> l.getText() != null && l.getText().contains(fragment), out);
        return out.isEmpty() ? null : out.get(0);
    }

    private static JTextField findField(Container root) {
        List<JTextField> out = new ArrayList<>();
        collect(root, JTextField.class, f -> true, out);
        return out.isEmpty() ? null : out.get(0);
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
}
