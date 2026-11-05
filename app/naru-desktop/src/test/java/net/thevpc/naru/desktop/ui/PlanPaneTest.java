package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.plan.NaruPlanItemSnapshot;
import net.thevpc.naru.api.plan.NaruPlanItemState;
import net.thevpc.naru.api.plan.NaruPlanListener;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.api.plan.NaruPlanSnapshot;
import net.thevpc.naru.api.plan.NaruPlanState;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.ToolTipManager;
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
import java.util.concurrent.CopyOnWriteArrayList;

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
    /** The board the pane was handed; the subscription assertions need the same instance it reads. */
    private RecordingPlanService source;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a toolkit to build widgets");
        NPath dir = NPath.ofTempFolder("naru-desktop-plan-pane");
        board = new ArrayList<>(List.of(
                new NaruPlanSnapshot("active-plan", "ship the cockpit",
                        "The tally is unreadable when the goal wraps.\n\n- fix the **tally**",
                        NaruPlanState.OPEN,
                        List.of(item("item-a", "write the rail", NaruPlanItemState.READY),
                                item("item-b", "polish the header", NaruPlanItemState.RUNNING),
                                item("item-c", "test it\n\n- run the suite\n- file the gaps",
                                        NaruPlanItemState.PENDING)), true),
                new NaruPlanSnapshot("dormant-plan", "an older idea", "", NaruPlanState.PENDING,
                        List.of(item("item-d", "something else", NaruPlanItemState.PENDING)), false)));
        writes = new ArrayList<>();
        source = new RecordingPlanService();
        onEdt(() -> {
            host = new InteractionHost();
            model = new NaruSessionModel(() -> null, dir, host);
            // Runnable::run keeps the test deterministic: a click reaches the service before the
            // assertion, while production writes use a background worker
            pane = new PlanPane(model, () -> source, Runnable::run);
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

    /** Wait until the board shows a label holding {@code fragment}, after something changed it. */
    private void awaitLabel(String fragment) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(60);
            if (Boolean.TRUE.equals(onEdtReturn(
                    () -> findLabelContaining(pane, fragment) != null))) {
                return;
            }
        }
        assertTrue(false, "the board never drew " + fragment + "; writes were " + writes);
    }

    // ── which plan is pointed at ──────────────────────────────────────────────

    @Test
    void everyPlanIsEditableAndTheActiveOneOffersDeactivation() throws Exception {
        assertNotNull(onEdtReturn(() -> findButton(pane, "⏸")), "the active plan can be deactivated");
        assertNotNull(onEdtReturn(() -> findButton(pane, "▶")), "the dormant plan can be activated");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "＋", "Add an item")).size(),
                "both registered plans offer add-an-item, active or not");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "×", "Remove this item")).size(),
                "only the open plan's items are drawn; the running item carries no remove control");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "🗑")).size(),
                "every registered plan can be deleted from its row");
        assertEquals(2, onEdtReturn(() -> findButtons(pane, "✎", "Rename this plan")).size(),
                "every registered plan can be renamed and re-described from its row");
    }

    @Test
    void theNewPlanControlCarriesNoWords() throws Exception {
        // the rail is narrow and every other control on this panel is a glyph; a text button in the
        // bar was both the ugliest row on the board and the only one that broke that rule
        assertNull(onEdtReturn(() -> findLabelStartingWith(pane, "New plan")));
        JButton newPlan = onEdtReturn(() -> newPlanButton());
        assertNotNull(newPlan, "the board always offers a new plan");
        assertEquals("＋", newPlan.getText());
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
        List<JButton> remove = onEdtReturn(() -> findButtons(pane, "×", "Remove this item"));
        onEdt(() -> remove.get(0).doClick());
        assertEquals(List.of("remove active-plan item-a"), writes);
    }

    // ── creating and editing plans ────────────────────────────────────────────

    @Test
    void theToolbarMakesANewPlanThroughTheSameDoor() throws Exception {
        JButton newPlan = onEdtReturn(() -> newPlanButton());
        assertNotNull(newPlan, "the board always offers a new plan");
        onEdt(newPlan::doClick);
        JTextField goal = onEdtReturn(() -> findField(pane));
        assertNotNull(goal, "the new-plan control opens a field for the goal");
        onEdt(() -> goal.setText("migrate the build"));
        submit(goal);
        assertEquals(List.of("create migrate the build"), writes);
    }

    @Test
    void theNewPlanEditorTakesTheWholeColumnWidth() throws Exception {
        // the editor row used to clamp to its own preferred width, so on a rail wider than that it
        // drew as a narrow field glued to one side instead of the full-width form the reader aimed
        // at; only a realised frame lays the column out, so this test opens one
        java.util.concurrent.atomic.AtomicReference<JFrame> frameBox = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<JTextField> goalBox = new java.util.concurrent.atomic.AtomicReference<>();
        // the board the reader clicks + on is most often the empty one, whose placeholder is the
        // only centred line in the column; it used to drag the editor's axis to the right
        onEdt(() -> {
            board = new ArrayList<>();
            pane.sessionModelChanged();
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !Boolean.TRUE.equals(onEdtReturn(
                        () -> findLabelStartingWith(pane, "No plan yet") != null))) {
            Thread.sleep(60);
        }
        onEdt(() -> {
            JFrame frame = new JFrame("plan-pane-width");
            frame.setUndecorated(true);
            frame.getContentPane().add(pane);
            frame.setSize(700, 520);
            frame.setVisible(true);
            frameBox.set(frame);
            newPlanButton().doClick();
            frame.validate();
            goalBox.set(findField(pane));
        });
        try {
            JTextField goal = goalBox.get();
            assertNotNull(goal, "the new-plan control opens a field for the goal");
            Container row = goal.getParent();
            Container column = row.getParent();
            assertTrue(column.getWidth() > 436,
                    "the fixture rail is wider than the editor's own preferred width: " + column.getSize());
            assertTrue(row.getWidth() >= column.getWidth() - 2,
                    "the create editor spans the board: row=" + row.getWidth()
                            + " column=" + column.getWidth());
            assertEquals(0, row.getX(),
                    "the create editor starts at the column's left edge, not its middle");
        } finally {
            onEdt(() -> frameBox.get().dispose());
        }
    }

    @Test
    void thePlanDescriptionIsEditedInAMarkdownSyntaxArea() throws Exception {
        click(onEdtReturn(() -> findRendered(pane, "fix the")));
        JTextArea area = onEdtReturn(() -> findArea(pane));
        assertTrue(area instanceof RSyntaxTextArea,
                "the prose editor is the desktop's own text widget, so undo and wrapping match");
        assertEquals(SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                ((RSyntaxTextArea) area).getSyntaxEditingStyle(),
                "the description field colours Markdown as the reader writes it");
    }

    @Test
    void aNewPlanKeepsTheParagraphTypedIntoIt() throws Exception {
        JButton newPlan = onEdtReturn(() -> newPlanButton());
        onEdt(newPlan::doClick);
        JTextField goal = onEdtReturn(() -> findField(pane));
        onEdt(() -> goal.setText("migrate the build"));
        JTextArea body = onEdtReturn(() -> findArea(pane));
        assertNotNull(body, "a plan is created with its description beside the goal, not after it");
        onEdt(() -> body.setText("first the reactor,\n\nthen the desktop"));
        commit();
        assertEquals(List.of("create migrate the build first the reactor,\n\nthen the desktop"), writes);
    }

    @Test
    void editingADormantPlansItemNeverTouchesTheActivePlan() throws Exception {
        // open the dormant plan first; item-d belongs to it, and the line must name that plan
        click(onEdtReturn(() -> findLabel(pane, "an older idea")));
        JLabel row = onEdtReturn(() -> findLabel(pane, "something else"));
        assertNotNull(row, "the dormant plan's item is on the board once its row is opened");
        click(row);
        JTextArea area = onEdtReturn(() -> findArea(pane));
        onEdt(() -> area.setText("something else, reworded"));
        commit();
        assertEquals(List.of("edit dormant-plan item-d something else, reworded"), writes);
    }

    @Test
    void addingAnItemToAClosedPlanOpensItFirstAndNamesIt() throws Exception {
        List<JButton> add = onEdtReturn(() -> findButtons(pane, "＋", "Add an item"));
        // the active plan is listed first; the second control belongs to the dormant plan
        onEdt(() -> add.get(1).doClick());
        JTextField key = onEdtReturn(() -> findField(pane));
        assertNotNull(key, "add opens a field in the plan it belongs to");
        JTextArea area = onEdtReturn(() -> findArea(pane));
        onEdt(() -> area.setText("land the docs"));
        commit();
        assertEquals(List.of("add dormant-plan land the docs"), writes);
    }

    // ── editing the text ──────────────────────────────────────────────────────

    @Test
    void clickOnAnItemOpensItsSentenceAndTheButtonSendsTheRewrite() throws Exception {
        JLabel row = onEdtReturn(() -> findLabel(pane, "write the rail"));
        assertNotNull(row, "the item is drawn as its description");
        click(row);
        JTextArea area = onEdtReturn(() -> findArea(pane));
        assertNotNull(area, "the click opened a body field in the row");
        assertEquals("write the rail", area.getText(),
                "the field starts as the sentence it replaces");
        onEdt(() -> area.setText("write the rail, then test --the flag"));
        commit();
        assertEquals(List.of("edit active-plan item-a write the rail, then test --the flag"), writes);
    }

    @Test
    void renamingAnItemIsASecondWriteOnTheSameRow() throws Exception {
        JLabel row = onEdtReturn(() -> findLabel(pane, "write the rail"));
        click(row);
        JTextField key = onEdtReturn(() -> findField(pane));
        assertEquals("item-a", key.getText(), "the key field starts as the handle it shows");
        onEdt(() -> key.setText("rail"));
        commit();
        assertEquals(List.of("rename active-plan item-a rail"), writes);
    }

    @Test
    void aKeyThatIsNotASingleWordIsRefusedBeforeItReachesThePlan() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextField key = onEdtReturn(() -> findField(pane));
        onEdt(() -> key.setText("two words"));
        commit();
        assertEquals(List.of(), writes, "a handle with a space in it cannot be referred to");
        assertNotNull(onEdtReturn(() -> findLabel(pane, "write the rail")), "and the row comes back");
    }

    @Test
    void theGoalIsRestatedThroughTheSameDoor() throws Exception {
        JLabel goal = onEdtReturn(() -> findLabel(pane, "ship the cockpit"));
        assertNotNull(goal, "the plan is drawn as its goal");
        click(goal);
        JTextField field = onEdtReturn(() -> findField(pane));
        onEdt(() -> field.setText("ship the cockpit and the rail"));
        // the plan already has prose, so Enter in the goal field steps into it rather than saving;
        // the save is the button or Ctrl+Enter
        commit();
        assertEquals(List.of("goal active-plan ship the cockpit and the rail"), writes);
    }

    @Test
    void thePlanDescriptionIsRenderedAndRewritable() throws Exception {
        JEditorPane body = onEdtReturn(() -> findRendered(pane, "fix the"));
        assertNotNull(body, "the plan's own prose is drawn as Markdown, not as one clipped line");
        assertTrue(onEdtReturn(() -> !body.getText().contains("**")),
                "the markup the reader wrote is drawn, not its asterisks");
        click(body);
        JTextArea area = onEdtReturn(() -> findArea(pane));
        assertEquals("The tally is unreadable when the goal wraps.\n\n- fix the **tally**",
                area.getText(), "the editor is handed the Markdown source, not the rendered HTML");
        onEdt(() -> area.setText("now it is readable"));
        // the prose editor shows the goal too, so a sentence retyped there has to be written, not
        // left in a field that only the ✎ on the header row would ever save
        JTextField goal = onEdtReturn(() -> findField(pane));
        onEdt(() -> goal.setText("ship the rail"));
        commit();
        assertEquals(List.of("goal active-plan ship the rail",
                "describe active-plan now it is readable"), writes);
    }

    @Test
    void anItemShowsItsWholeBodyNotJustItsFirstLine() throws Exception {
        assertNotNull(onEdtReturn(() -> findLabel(pane, "test it")),
                "an item with a body still scans as one headline");
        JEditorPane body = onEdtReturn(() -> findRendered(pane, "run the suite"));
        assertNotNull(body, "and the rest of it is shown rather than clipped into a tooltip");
    }

    @Test
    void aPlanWithoutADescriptionOffersToWriteOne() throws Exception {
        // the active plan of the fixture already has prose; the dormant one is the blank case
        click(onEdtReturn(() -> findLabel(pane, "an older idea")));
        click(onEdtReturn(() -> findLabelStartingWith(pane, "＋ add a description")));
        JTextArea area = onEdtReturn(() -> findArea(pane));
        assertNotNull(area, "the hint opens the same field a written description is edited in");
        onEdt(() -> area.setText("because the old build died on JDK 25"));
        commit();
        assertEquals(List.of("describe dormant-plan because the old build died on JDK 25"), writes);
    }

    @Test
    void theAddControlOpensABlankFieldAndSendsWhatIsTypedIntoIt() throws Exception {
        JButton plus = onEdtReturn(() -> findButton(pane, "＋", "Add an item"));
        assertNotNull(plus, "the active plan offers add-an-item");
        onEdt(plus::doClick);
        JTextArea area = onEdtReturn(() -> findArea(pane));
        assertNotNull(area, "add an item starts a field, not a dialog");
        onEdt(() -> area.setText("land the docs"));
        commit();
        assertEquals(List.of("add active-plan land the docs"), writes);
    }

    @Test
    void aReadyItemCanMoveEarlierAndLater() throws Exception {
        // item-a is first and item-c last, so each has one direction left; the middle item is the
        // running one and carries no control at all
        List<JButton> down = onEdtReturn(() -> findButtons(pane, "↓"));
        List<JButton> up = onEdtReturn(() -> findButtons(pane, "↑"));
        assertEquals(1, down.size(), "only the first item can move later");
        assertEquals(1, up.size(), "only the last item can move earlier");
        onEdt(() -> up.get(0).doClick());
        assertEquals(List.of("move active-plan item-c 1"), writes);
    }

    @Test
    void aRunningItemRefusesToOpenAField() throws Exception {
        JLabel busy = onEdtReturn(() -> findLabel(pane, "polish the header"));
        assertNotNull(busy, "the running item is still on the board");
        assertEquals(0, onEdtReturn(() -> editHandlers(busy).size()),
                "its text is the instruction a task is working from, so the row is not clickable");
        assertNull(onEdtReturn(() -> findField(pane)));
        assertEquals(List.of(), writes);
    }

    @Test
    void aRepaintWhileTheReaderTypesLeavesTheFieldAlone() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextArea area = onEdtReturn(() -> findArea(pane));
        onEdt(() -> area.setText("half a sentence"));
        // a streaming turn fires the model listener constantly; none of it may swallow the draft
        onEdt(pane::sessionModelChanged);
        Thread.sleep(600);
        JTextArea still = onEdtReturn(() -> findArea(pane));
        assertSame(area, still, "the board must not repaint over an open editor");
        assertEquals("half a sentence", still.getText());
        assertEquals(List.of(), writes, "waiting is not sending");
    }

    @Test
    void escapeClosesTheFieldWithoutSendingAnything() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextField key = onEdtReturn(() -> findField(pane));
        onEdt(() -> key.setText("never mind"));
        pressEscape(key);
        assertNull(onEdtReturn(() -> findField(pane)), "escape closes the editor");
        assertEquals(List.of(), writes);
        assertNotNull(onEdtReturn(() -> findLabel(pane, "write the rail")), "the row comes back");
    }

    @Test
    void anEmptySentenceSendsNothing() throws Exception {
        click(onEdtReturn(() -> findLabel(pane, "write the rail")));
        JTextArea area = onEdtReturn(() -> findArea(pane));
        onEdt(() -> area.setText("   "));
        JButton commit = onEdtReturn(() -> findButton(pane, "✓"));
        assertNotNull(commit, "the editor carries a commit button");
        onEdt(commit::doClick);
        assertEquals(List.of(), writes, "a blank description is not a plan item");
        assertNull(onEdtReturn(() -> findField(pane)), "and the editor still closes");
    }

    // ── the board as a task changes it ────────────────────────────────────────

    @Test
    void thePanelSubscribesToTheBoardItReads() throws Exception {
        RecordingPlanService service = source;
        assertEquals(1, service.listeners.size(),
                "one subscription per pane, so a session switch does not accumulate them");
    }

    @Test
    void aChangeFromTheExtensionIsDrawnWithoutTheReaderClicking() throws Exception {
        RecordingPlanService service = source;
        // a task reports progress through the plan tool: the panel is told, and nothing else happens
        board = new ArrayList<>(List.of(new NaruPlanSnapshot("active-plan",
                "ship the cockpit and the rail", "", NaruPlanState.OPEN,
                List.of(item("item-a", "write the rail", NaruPlanItemState.DONE)), true)));
        new Thread(service.listeners.get(0)::plansChanged).start();
        awaitLabel("ship the cockpit and the rail");
        assertNotNull(onEdtReturn(() -> findLabelContaining(pane, "1/1 · open")),
                "the item the task finished is drawn as done");
        assertEquals(List.of(), writes, "a push is not a write");
    }

    @Test
    void disposingThePanelDropsItsSubscription() throws Exception {
        RecordingPlanService service = source;
        onEdt(pane::dispose);
        assertTrue(service.listeners.isEmpty(), "a pane nobody shows must not keep reading the graph");
        pane = null;
    }

    // ── the service seam ──────────────────────────────────────────────────────

    /**
     * The service seam, recorded as one readable line per call instead of touching a real graph.
     */
    private final class RecordingPlanService implements NaruPlanService {

        final List<NaruPlanListener> listeners = new CopyOnWriteArrayList<>();

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
        public void addPlanListener(NaruPlanListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePlanListener(NaruPlanListener listener) {
            listeners.remove(listener);
        }

        @Override
        public NaruPlanSnapshot createPlan(String goal) {
            writes.add("create " + goal);
            return new NaruPlanSnapshot("created-plan", goal, NaruPlanState.PENDING, List.of(), false);
        }

        @Override
        public NaruPlanSnapshot createPlan(String goal, String description) {
            if (description == null || description.isBlank()) {
                return createPlan(goal);
            }
            writes.add("create " + goal + " " + description);
            return new NaruPlanSnapshot("created-plan", goal, description, NaruPlanState.PENDING,
                    List.of(), false);
        }

        @Override
        public NaruPlanSnapshot setGoal(String planId, String goal) {
            writes.add("goal " + planId + " " + goal);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot setDescription(String planId, String description) {
            writes.add("describe " + planId + " " + description);
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
        public NaruPlanSnapshot renameItem(String planId, String itemId, String key) {
            writes.add("rename " + planId + " " + itemId + " " + key);
            return board.get(0);
        }

        @Override
        public NaruPlanSnapshot moveItem(String planId, String itemId, int newIndex) {
            writes.add("move " + planId + " " + itemId + " " + newIndex);
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

    /** Send the click that turns a row into a field. */
    private void click(Component label) throws Exception {
        assertNotNull(label, "there is a row to click");
        onEdt(() -> {
            MouseListener[] listeners = ((JComponent) label).getMouseListeners();
            assertTrue(listeners.length > 0, "the text is not clickable: " + label);
            MouseEvent click = new MouseEvent(label, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false);
            for (MouseListener l : listeners) {
                l.mouseClicked(click);
            }
        });
    }

    private void commit() throws Exception {
        JButton ok = onEdtReturn(() -> findButton(pane, "✓"));
        assertNotNull(ok, "an editor always carries its save button");
        onEdt(ok::doClick);
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

    private JButton newPlanButton() {
        return findButton(pane, "＋", "New plan");
    }

    private static JButton findButton(Container root, String glyph) {
        List<JButton> found = findButtons(root, glyph, null);
        return found.isEmpty() ? null : found.get(0);
    }

    private static JButton findButton(Container root, String glyph, String tipFragment) {
        List<JButton> found = findButtons(root, glyph, tipFragment);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<JButton> findButtons(Container root, String glyph) {
        return findButtons(root, glyph, null);
    }

    /**
     * Buttons by glyph, optionally narrowed to those whose tooltip says {@code tipFragment}. Several
     * controls on this board share a glyph — the bar's ＋ makes a plan, a row's ＋ makes an item — and
     * what distinguishes them is the sentence the tooltip carries.
     */
    private static List<JButton> findButtons(Container root, String glyph, String tipFragment) {
        List<JButton> out = new ArrayList<>();
        collect(root, JButton.class, b -> glyph.equals(b.getText())
                && (tipFragment == null || (b.getToolTipText() != null
                && b.getToolTipText().startsWith(tipFragment))), out);
        return out;
    }

    private static JLabel findLabel(Container root, String text) {
        List<JLabel> out = new ArrayList<>();
        collect(root, JLabel.class, l -> text.equals(l.getText()), out);
        return out.isEmpty() ? null : out.get(0);
    }

    /**
     * The listeners that would open an editor. Swing's shared {@link ToolTipManager} attaches
     * itself to any component that carries a tooltip, so a raw listener count says nothing about
     * whether the row answers a click.
     */
    private static List<MouseListener> editHandlers(Component c) {
        List<MouseListener> out = new ArrayList<>();
        for (MouseListener l : ((JComponent) c).getMouseListeners()) {
            if (!(l instanceof ToolTipManager)) {
                out.add(l);
            }
        }
        return out;
    }

    private static JLabel findLabelStartingWith(Container root, String prefix) {
        List<JLabel> out = new ArrayList<>();
        collect(root, JLabel.class, l -> l.getText() != null && l.getText().startsWith(prefix), out);
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

    private static JTextArea findArea(Container root) {
        List<JTextArea> out = new ArrayList<>();
        collect(root, JTextArea.class, a -> true, out);
        return out.isEmpty() ? null : out.get(0);
    }

    /** A rendered Markdown body holding {@code fragment} of its source text. */
    private static JEditorPane findRendered(Container root, String fragment) {
        List<JEditorPane> out = new ArrayList<>();
        collect(root, JEditorPane.class, p -> "text/html".equals(p.getContentType())
                && p.getText().contains(fragment), out);
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
