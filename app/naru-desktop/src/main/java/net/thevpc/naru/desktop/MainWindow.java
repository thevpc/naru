package net.thevpc.naru.desktop;

import net.thevpc.common.swing.plaf.JPlafMenu;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.InputPane;
import net.thevpc.naru.desktop.ui.PermissionsPane;
import net.thevpc.naru.desktop.ui.StatusBar;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.naru.desktop.ui.ToolsPane;
import net.thevpc.naru.desktop.ui.TranscriptPane;
import net.thevpc.nuts.io.NPath;
import net.thevpc.swing.plaf.UIPlaf;
import net.thevpc.swing.plaf.UIPlafManager;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The v1 window: one frame, a project switcher, transcript on the left, side panels as tabs.
 * <p>
 * The layout is {@code JSplitPane} + {@code JTabbedPane} by decision, not preference —
 * {@code JDockPane} crashed removing the visible window, its anchor setter is an empty stub, and
 * horizontal divider positions do not stick (Phase 0 spike 4). See
 * {@code documentation/plans/desktop-gui-phase0.md}.
 * <p>
 * One window also settles plan §3.1: {@code setSharedWorkspaceInstance()} is process-global, so a
 * second window with a different project would fight over it. Everything here resolves through
 * the explicit {@link NaruSessionModel} of the selected project; nothing reads global state.
 */
public final class MainWindow extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);
    private static final String KEY_RECENT = "recentProjects";
    private static final String KEY_PLAF = "plaf";
    private static final String KEY_DIVIDER = "divider";

    private final JPanel center = new JPanel(new BorderLayout());
    private final DefaultComboBoxModel<String> projects = new DefaultComboBoxModel<>();
    private final JComboBox<String> switcher = new JComboBox<>(projects);
    private final List<NPath> recent = new ArrayList<>();

    private TranscriptPane transcript;
    private InputPane input;
    private StatusBar statusBar;
    private ToolsPane tools;
    private PermissionsPane permissions;
    private NaruSessionModel session;
    private InteractionHost host;
    private boolean switching;

    public MainWindow() {
        super("NARU");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        add(topBar(), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        addWelcome();
        restoreRecent();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeSession();
                persist();
                dispose();
                // the engine holds worker threads; an explicit exit is the honest shutdown here
                System.exit(0);
            }
        });
    }

    private JPanel topBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JLabel label = new JLabel("Project");
        label.setFont(label.getFont().deriveFont(Font.PLAIN));
        bar.add(label);
        switcher.setToolTipText("One window, many projects: switching closes the current session");
        switcher.addActionListener(e -> {
            if (!switching) {
                int i = switcher.getSelectedIndex();
                if (i >= 0) {
                    openProject(recent.get(i));
                }
            }
        });
        bar.add(switcher);
        JLabel plafLabel = new JLabel("Theme");
        plafLabel.setFont(plafLabel.getFont().deriveFont(Font.PLAIN));
        bar.add(plafLabel);
        // JPlafMenu applies the look-and-feel itself; this listener only remembers the choice
        JPlafMenu plaf = new JPlafMenu(this);
        plaf.addLookAndFeelChangeListener(e -> {
            // persist the manager's current id rather than the event payload: the payload is a
            // LAF class name, and apply() expects the plaf id
            UIPlaf current = UIPlafManager.getCurrentManager().getCurrent();
            if (current != null) {
                PREFS.put(KEY_PLAF, current.getId());
            }
        });
        bar.add(plaf);
        return bar;
    }

    private void addWelcome() {
        JPanel p = new JPanel(new BorderLayout());
        JLabel hello = new JLabel("<html><div style='padding:18px'>"
                + "<b>NARU desktop</b><br>"
                + "Pick a project folder above, or start with <tt>--project &lt;dir&gt;</tt>."
                + "</div></html>");
        hello.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        p.add(hello, BorderLayout.NORTH);
        center.removeAll();
        center.add(p, BorderLayout.CENTER);
        center.revalidate();
    }

    /** Close whatever is open and bind a fresh session to {@code dir}. */
    public void openProject(NPath dir) {
        SwingUtilities.invokeLater(() -> {
            closeSession();
            host = new InteractionHost();
            // the queue drains from the first frame, so whatever the engine writes while the
            // workspace is still resolving shows up instead of being buffered behind the boot
            host.startFlushTimer();
            session = new NaruSessionModel(() -> newWorkspace(dir), dir, host);
            transcript = new TranscriptPane(session);
            // the §6 jump, from the card the user actually reads
            transcript.setJumpToPermissions(ruleId -> openPermissions(ruleId));
            input = new InputPane(session);
            statusBar = new StatusBar(session);
            TaskPane tasks = new TaskPane(session);

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, transcript,
                    sideTabs(tasks, session));
            split.setResizeWeight(0.74);
            placeDivider(split);
            // once the window has been laid out the split knows its real width, which is the only
            // honest ceiling for a restored divider
            SwingUtilities.invokeLater(() -> placeDivider(split));
            split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                    e -> PREFS.putInt(KEY_DIVIDER, split.getDividerLocation()));

            JPanel body = new JPanel(new BorderLayout());
            body.add(split, BorderLayout.CENTER);
            body.add(input, BorderLayout.SOUTH);
            body.add(statusBar, BorderLayout.NORTH);

            center.removeAll();
            center.add(body, BorderLayout.CENTER);
            center.revalidate();
            center.repaint();
            remember(dir);
            // returns at once: the boot itself runs on a worker and reports back through the
            // session model, so the window is painted and responsive while extensions resolve
            session.start();
            input.focusInput();
        });
    }

    private JTabbedPane sideTabs;

    /** The narrowest the tab panel may be squeezed to: a rule row needs to be readable. */
    private static final int MIN_SIDE_WIDTH = 380;

    /**
     * Put the transcript/tab-panel divider where the user left it, but never so far right that the
     * tab panel disappears. The saved value is a pixel count taken from whatever window it was
     * dragged in — possibly a wider one — and the transcript's preferred width is the width of its
     * longest line, so an unclamped restore parks the divider past the right edge: Tools,
     * Permissions and the §6 jump all still work, in a panel nobody can see or click.
     */
    private void placeDivider(JSplitPane split) {
        int available = split.getWidth() > 0 ? split.getWidth()
                : Math.max(getContentPane().getWidth(), getWidth() - 16);
        if (available <= MIN_SIDE_WIDTH * 2) {
            // nothing sensible to clamp to; let resizeWeight decide until the window has a width
            return;
        }
        int saved = PREFS.getInt(KEY_DIVIDER, -1);
        int at = saved > 100 ? Math.min(saved, available - MIN_SIDE_WIDTH)
                : (int) (available * 0.74);
        split.setDividerLocation(Math.max(0, at));
    }

    private JTabbedPane sideTabs(TaskPane tasks, NaruSessionModel session) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Tasks", tasks);
        // E1 landed the typed lifecycle, so this tab is real data now rather than a placeholder
        tools = new ToolsPane(session.tools());
        tabs.addTab("Tools", tools);
        // moved from Phase 4 by the owner: §3.3 makes this the v1 answer to an approval prompt,
        // and §6 promises a jump into it from a permission denial
        permissions = new PermissionsPane(session);
        tabs.addTab("Permissions", permissions);
        tools.setJumpToPermissions(ruleId -> openPermissions(ruleId));
        // the event log is Phase 3
        tabs.addTab("Log", new JLabel("<html><div style='padding:14px;color:gray'>The event log "
                + "arrives with Phase 3 (plan E3).</div></html>"));
        this.sideTabs = tabs;
        return tabs;
    }

    /**
     * Jump from a denial to the rule that caused it (plan §6). A null id means the default effect
     * denied the call, which is still worth showing — the fallback combo is what to change — but no
     * row can be selected for it.
     */
    public void openPermissions(String ruleId) {
        if (!selectSideTab("Permissions")) {
            return;
        }
        PermissionsPane pane = permissions;
        if (pane != null) {
            pane.selectRule(ruleId);
        }
    }

    /**
     * Show one of the side tabs by title. The gate harness needs it: a screenshot of the transcript
     * cannot be evidence that the Tools tab works, and this is the only way to look at it from
     * outside the class.
     */
    public boolean selectSideTab(String title) {
        JTabbedPane tabs = sideTabs;
        if (tabs == null) {
            return false;
        }
        int i = tabs.indexOfTab(title);
        if (i < 0) {
            return false;
        }
        tabs.setSelectedIndex(i);
        return true;
    }

    /** The side tab now showing, or null before a session exists. Harness-facing, like the setter. */
    public String selectedSideTab() {
        JTabbedPane tabs = sideTabs;
        return tabs == null || tabs.getSelectedIndex() < 0 ? null : tabs.getTitleAt(tabs.getSelectedIndex());
    }

    /**
     * The rule the Permissions tab has selected, for a gate run that has to prove a jump landed on
     * the right row rather than merely switched tabs.
     */
    public String selectedPermissionRule() {
        PermissionsPane pane = permissions;
        return pane == null ? null : pane.selectedRuleId();
    }

    /**
     * What the selected side tab shows. A gate run photographs the panel it just opened, and the
     * side panel is the part of a 1400-pixel window a full-screen capture tends to cut off.
     */
    public java.awt.Component selectedSideComponent() {
        JTabbedPane tabs = sideTabs;
        return tabs == null ? null : tabs.getSelectedComponent();
    }

    /**
     * A workspace per project, deliberately not installed as the shared instance: with one window
     * per JVM the shared slot would be a second, global source of truth for widgets (plan §3.1).
     */
    private static NaruWorkspace newWorkspace(NPath dir) {
        return NaruWorkspaceBuilder.of().projectDirectory(dir).build();
    }

    private void closeSession() {
        if (transcript != null) {
            transcript.dispose();
        }
        // both panes listen to models the session owns, so they unsubscribe before the shutdown
        // rather than leaving a listener on a model that is about to fire one last time
        if (tools != null) {
            tools.dispose();
        }
        if (permissions != null) {
            permissions.dispose();
        }
        if (session != null) {
            session.shutdown();
            session = null;
        }
        host = null;
    }

    private void remember(NPath dir) {
        String s = dir.toString();
        if (!recent.isEmpty() && recent.get(0).equals(dir)) {
            return;
        }
        recent.add(0, dir);
        rebuildSwitcher();
        persist();
    }

    private void rebuildSwitcher() {
        switching = true;
        projects.removeAllElements();
        for (NPath p : recent) {
            projects.addElement(p.toString());
        }
        projects.setSelectedItem(recent.isEmpty() ? null : recent.get(0).toString());
        switching = false;
    }

    private void restoreRecent() {
        String csv = PREFS.get(KEY_RECENT, "");
        for (String s : csv.split(";")) {
            if (!s.isBlank()) {
                recent.add(NPath.of(s));
            }
        }
        if (!recent.isEmpty()) {
            rebuildSwitcher();
        }
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (NPath p : recent) {
            if (kept++ == 8) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(p);
        }
        PREFS.put(KEY_RECENT, sb.toString());
    }

    public NaruSessionModel session() {
        return session;
    }

    /** The host behind the current session, for the gate harness's measurements. */
    public InteractionHost host() {
        return host;
    }

    /** The theme remembered at last close, applied while the frame is still empty. */
    public static void restoreTheme(JFrame w) {
        String plaf = PREFS.get(KEY_PLAF, null);
        if (plaf == null || plaf.isBlank()) {
            return;
        }
        try {
            UIPlafManager.getCurrentManager().apply(plaf);
            SwingUtilities.updateComponentTreeUI(w);
        } catch (RuntimeException ignored) {
            // an uninstalled look-and-feel is not a reason to refuse to start
        }
    }
}
