package net.thevpc.naru.desktop;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.plan.NaruPlanService;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.SessionGrouping;
import net.thevpc.naru.desktop.ui.InputPane;
import net.thevpc.naru.desktop.ui.StatusBar;
import net.thevpc.naru.desktop.ui.StatusDot;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.naru.desktop.ui.InspectorPane;
import net.thevpc.naru.desktop.ui.PlanPane;
import net.thevpc.naru.desktop.ui.TranscriptPane;
import net.thevpc.naru.desktop.ui.UiPalette;
import net.thevpc.naru.desktop.ui.WorkbenchPane;
import net.thevpc.naru.desktop.ui.SettingsPane;
import net.thevpc.naru.desktop.ui.SplitPanes;
import net.thevpc.naru.desktop.ui.TextAreas;
import net.thevpc.naru.desktop.ui.BrandMark;
import net.thevpc.naru.desktop.ui.HeaderBar;
import net.thevpc.naru.desktop.ui.HoverTint;
import net.thevpc.naru.desktop.ui.TabGlyphs;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.reflect.NClassLoader;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-centric IDE shell. Sessions are the top-level navigation; each session owns its task
 * task tree, session log, and task-selected conversation view. Session identity is independent
 * from each task's project and working directory.
 * <p>
 * The layout is {@code JSplitPane} + {@code JTabbedPane} by decision, not preference —
 * {@code JDockPane} crashed removing the visible window, its anchor setter is an empty stub, and
 * horizontal divider positions do not stick (Phase 0 spike 4). See
 * {@code documentation/plans/desktop-gui-phase0.md}.
 * <p>
 * Every session resolves through its explicit {@link NaruSessionModel}; no widget reads global
 * workspace state.
 */
public final class MainWindow extends JFrame {

    /**
     * How long the close path waits for session teardowns before exiting anyway. Bounded rather
     * than trusted: a hibernate stops the world to persist and a stop-the-world waits out
     * whatever a worker is mid-way through, so a wedged provider must not outlive the window it
     * was answering for. The normal teardown finishes well inside this.
     */
    private static final long CLOSE_GRACE_MS = 5000;
    /**
     * How long a reopen waits for the previous activation's teardown before touching the store
     * anyway. Generous compared with the exit budget: this wait sits on the boot worker behind
     * the loading overlay, and letting a slow toolset release finish is the whole point.
     */
    private static final long REOPEN_TEARDOWN_WAIT_MS = 30000;

    /** How wide a band along the window edge grabs a resize drag. */
    private static final int EDGE = 6;
    /** The smallest window the layout can be dragged to: below this the panes have nothing left to show. */
    private static final int MIN_WIDTH = 720;
    private static final int MIN_HEIGHT = 480;


    private final JPanel center = new JPanel(new BorderLayout());
    private DefaultListModel<SessionEntry> sessionRows = new DefaultListModel<>();
    private final JList<SessionEntry> sessionList = new JList<>(sessionRows) {
        /**
         * A {@code JList} owns one tooltip for the whole widget, so a row's own words never
         * reach the pointer. Group rows use this for the description the user typed for the
         * project; every other row keeps the default.
         */
        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            int row = locationToIndex(e.getPoint());
            if (row >= 0 && row < getModel().getSize()) {
                SessionEntry entry = getModel().getElementAt(row);
                if (entry != null && entry.isGroup() && entry.header.project() != null) {
                    String text = entry.header.project().description();
                    if (text != null && !text.isBlank()) return text;
                }
            }
            return super.getToolTipText(e);
        }
    };
    /** The sessions sidebar, kept so its width can follow the interface size like everything else. */
    private JPanel sessionRailPanel;
    private final javax.swing.JTextField sessionFilter = new javax.swing.JTextField();
    private final List<SessionEntry> sessions = new ArrayList<>();
    /**
     * Projects whose saved catalog has already been loaded into the sidebar. The catalog is
     * disk state that does not change while the desktop runs, so reading it twice would only
     * risk listing the same session twice.
     */
    private final java.util.Set<String> catalogLoadedProjects = new java.util.HashSet<>();
    private NPath projectDirectory;
    /**
     * A session uuid handed in on the command line. It is consumed by the first catalog load that
     * finishes, because the reopen is a request about a listing that has not been built yet.
     */
    private String openSessionUuid;

    private TranscriptPane transcript;
    private TranscriptPane sessionLogPane;
    private InputPane input;
    private StatusBar statusBar;
    private TaskPane taskPane;
    private WorkbenchPane workbench;
    private InspectorPane inspector;
    private PlanPane planPane;
    private NaruSessionModel session;
    private InteractionHost host;
    private boolean maximized;
    private boolean resizeListenerInstalled;
    private final MouseAdapter resizeListener = new MouseAdapter() {
        private int direction;
        private Point origin;
        private java.awt.Rectangle bounds;

        @Override public void mouseMoved(MouseEvent e) { hover(e); }

        @Override public void mouseExited(MouseEvent e) {
            if (origin != null) return;
            direction = 0;
            Component src = e.getComponent();
            if (isResizeCursor(src.getCursor())) src.setCursor(null);
        }

        @Override public void mousePressed(MouseEvent e) {
            Point where = inside(e);
            direction = e.getButton() == MouseEvent.BUTTON1 && where != null && !onDivider(where)
                    ? resizeDirection(where) : 0;
            origin = direction == 0 ? null : e.getLocationOnScreen();
            bounds = direction == 0 ? null : getBounds();
        }

        @Override public void mouseDragged(MouseEvent e) {
            if (origin == null) return;
            Point now = e.getLocationOnScreen();
            int dx = now.x - origin.x, dy = now.y - origin.y;
            java.awt.Rectangle b = new java.awt.Rectangle(bounds);
            if ((direction & 1) != 0) { b.x += dx; b.width -= dx; }
            if ((direction & 2) != 0) b.width += dx;
            if ((direction & 4) != 0) { b.y += dy; b.height -= dy; }
            if ((direction & 8) != 0) b.height += dy;
            if (b.width < MIN_WIDTH) b.width = MIN_WIDTH;
            if (b.height < MIN_HEIGHT) b.height = MIN_HEIGHT;
            setBounds(b);
            if (maximized && !b.equals(screenWorkArea())) maximized = false;
        }

        @Override public void mouseReleased(MouseEvent e) {
            origin = null;
            hover(e);
        }

        private void hover(MouseEvent e) {
            if (origin != null) return;
            Point where = inside(e);
            int newDir = where == null ? 0 : resizeDirection(where);
            direction = newDir;
            Component src = e.getComponent();
            if (newDir != 0) {
                Cursor c = resizeCursor(newDir);
                src.setCursor(c);
                setCursor(c);
            } else {
                if (isResizeCursor(src.getCursor())) src.setCursor(null);
                setCursor(Cursor.getDefaultCursor());
            }
        }

        private Point inside(MouseEvent e) {
            if (!isShowing()) return null;
            Point onScreen = e.getLocationOnScreen();
            Point here = getLocationOnScreen();
            return new Point(onScreen.x - here.x, onScreen.y - here.y);
        }

        private boolean onDivider(Point where) {
            if (where.x < EDGE || where.x > getWidth() - EDGE
                    || where.y < EDGE || where.y > getHeight() - EDGE) {
                return false;
            }
            return findComponentAt(where.x, where.y) instanceof javax.swing.plaf.basic.BasicSplitPaneDivider;
        }
    };
    private final java.awt.event.AWTEventListener resizeAWTListener = new java.awt.event.AWTEventListener() {
        public void eventDispatched(java.awt.AWTEvent ev) {
            if (!(ev instanceof MouseEvent)) return;
            MouseEvent e = (MouseEvent) ev;
            Component src = e.getComponent();
            if (src == null) return;
            java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(src);
            if (w != MainWindow.this && src != MainWindow.this) return;
            switch (e.getID()) {
                case MouseEvent.MOUSE_MOVED -> resizeListener.mouseMoved(e);
                case MouseEvent.MOUSE_EXITED -> resizeListener.mouseExited(e);
                case MouseEvent.MOUSE_PRESSED -> resizeListener.mousePressed(e);
                case MouseEvent.MOUSE_DRAGGED -> resizeListener.mouseDragged(e);
                case MouseEvent.MOUSE_RELEASED -> resizeListener.mouseReleased(e);
                default -> { }
            }
        }
    };

    /** Where the window was before it went full screen, for the restore half of {@link #toggleMaximize()}. */
    private java.awt.Rectangle restoredBounds;
    /** The frame cursor already asked for, so hovering an edge does not re-send the same request. */
    private Cursor appliedCursor;
    private final JLabel taskScopeHeader = new JLabel("No task selected");
    private NaruSessionModel.Listener selectionSync;
    private NaruSessionModel.Listener loadingListener;
    private NaruSessionModel.Listener sessionListSync;
    private boolean sessionFilterBound;
    private long sessionSwitchGeneration;
    /** The transcript/composer divider, saved across sessions so the user keeps the room they made. */
    private int composerDivider;
    private JSplitPane composerSplit;
    private JPanel loadingPanel;
    private JLabel loadingLabel;

    public MainWindow() {
        super("NARU");
        setUndecorated(true);
        java.net.URL icon = MainWindow.class.getResource("/net/thevpc/naru/desktop/naru.png");
        if (icon != null) setIconImage(new javax.swing.ImageIcon(icon).getImage());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        add(topBar(), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        installLoadingOverlay();
        installNavigationKeys();
        installResizeSupport();
        addWelcome();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // The window goes away first: the teardown below can take a while (a save stops
                // the world, a toolset close may kill a server process) and it used to run right
                // here on the EDT — the app froze on close and never reached the exit below.
                disposeSessionViews();
                dispose();
                List<NaruSessionModel> closing = new ArrayList<>();
                for (SessionEntry entry : sessions) {
                    // a row that was closed but never reopened still has a teardown draining
                    NaruSessionModel model = entry.model != null ? entry.model : entry.pendingTeardown;
                    if (model != null) closing.add(model);
                }
                for (NaruSessionModel model : closing) {
                    try {
                        model.shutdown();
                    } catch (RuntimeException ignored) {
                        // one broken session must not be what keeps the process alive
                    }
                }
                // The engine holds worker threads; an explicit exit is the honest shutdown here.
                // Give the teardowns their budget, then exit regardless — the window is gone and
                // nothing the engine is still doing deserves to keep the app running.
                Thread exit = new Thread(() -> {
                    long deadline = System.currentTimeMillis() + CLOSE_GRACE_MS;
                    for (NaruSessionModel model : closing) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        model.awaitShutdown(remaining);
                    }
                    System.exit(0);
                }, "naru-exit");
                exit.setDaemon(true);
                exit.start();
            }
        });
        sessionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SessionEntry item = sessionList.getSelectedValue();
                // group rows are labels, not sessions: they cannot be opened, hibernated
                // or renamed; the context menu on a row is the project's own
                if (item != null && item.isGroup()) return;
                // Selecting a hibernated row is intentionally inert. Users may want to
                // rename or delete it without starting its engine; opening is a separate action.
                if (item != null && item.model != null && item.model != session) {
                    long generation = ++sessionSwitchGeneration;
                    showLoading("Opening " + item.toString() + "…");
                    Timer switchDelay = new Timer(70, event -> {
                        if (generation == sessionSwitchGeneration) activateSession(item);
                    });
                    switchDelay.setRepeats(false); switchDelay.start();
                } else if (item != null && item.model == null) {
                    ++sessionSwitchGeneration;
                    showHibernatedSession(item);
                }
            }
        });
        sessionList.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("F2"), "rename-row");
        sessionList.getActionMap().put("rename-row", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // F2 follows the row: a group header renames its project, a session row its name.
                SessionEntry entry = sessionList.getSelectedValue();
                if (entry != null && entry.isGroup()) renameProjectUI(entry);
                else renameSelectedSession();
            }
        });
        sessionList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && sessionList.locationToIndex(e.getPoint()) >= 0) {
                    SessionEntry entry = sessionList.getSelectedValue();
                    if (entry != null && !entry.isGroup()) activateSession(entry);
                }
            }
            @Override public void mousePressed(MouseEvent e) {
                GroupAction action = javax.swing.SwingUtilities.isLeftMouseButton(e)
                        && !e.isPopupTrigger() ? groupActionAt(e.getPoint()) : null;
                if (action != null) {
                    SessionEntry group = groupRowAt(e.getPoint());
                    if (group != null) {
                        if (action == GroupAction.NEW_SESSION) createSession(group.header.projectId());
                        else groupPropsUI(group);
                        e.consume();
                        return;
                    }
                }
                showMenu(e);
            }
            @Override public void mouseReleased(MouseEvent e) { showMenu(e); }
            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = sessionList.locationToIndex(e.getPoint());
                if (row < 0) return;
                sessionList.setSelectedIndex(row);
                SessionEntry entry = sessionList.getSelectedValue();
                if (entry == null) return;
                javax.swing.JPopupMenu menu = entry.isGroup() ? groupMenu(entry) : sessionMenu(entry);
                menu.show(sessionList, e.getX(), e.getY());
            }
        });
    }

    /**
     * The width of one action painted at the right edge of a group row. The renderer lays its
     * chips out in bands of exactly this size and the hit-test reads the same constant, so a
     * click target cannot drift away from the glyph it belongs to.
     */
    private static final int GROUP_ACTION_BAND = 26;

    /** The two actions a group row offers at its right edge. */
    private enum GroupAction { NEW_SESSION, SETTINGS }

    /** Which group action the point lands on, or null when it is not on one. */
    private GroupAction groupActionAt(java.awt.Point p) {
        SessionEntry group = groupRowAt(p);
        if (group == null || group.header.unknown()) {
            return null;
        }
        int row = sessionList.locationToIndex(p);
        java.awt.Rectangle bounds = sessionList.getCellBounds(row, row);
        if (bounds == null || p.x < bounds.x + bounds.width - 2 * GROUP_ACTION_BAND) {
            return null;
        }
        return p.x >= bounds.x + bounds.width - GROUP_ACTION_BAND
                ? GroupAction.NEW_SESSION : GroupAction.SETTINGS;
    }

    /** The group row at a point, or null when the point is not on one. */
    private SessionEntry groupRowAt(java.awt.Point p) {
        int row = sessionList.locationToIndex(p);
        if (row < 0 || row >= sessionList.getModel().getSize()) return null;
        SessionEntry entry = sessionList.getModel().getElementAt(row);
        return entry != null && entry.isGroup() ? entry : null;
    }

    /** The popup of a session row: open/close, rename, move, delete, and the way into the tree. */
    private javax.swing.JPopupMenu sessionMenu(SessionEntry entry) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem openClose = new javax.swing.JMenuItem(entry.isOpen() ? "Close session" : "Open session");
        openClose.addActionListener(x -> {
            if (entry.isOpen()) hibernateSession(entry);
            else activateSession(entry);
        });
        menu.add(openClose);
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename session…");
        rename.addActionListener(x -> renameSelectedSession()); menu.add(rename);
        javax.swing.JMenuItem titler = new javax.swing.JMenuItem("Generate title…");
        titler.addActionListener(x -> regenerateSessionTitle(entry)); menu.add(titler);
        javax.swing.JMenuItem move = new javax.swing.JMenuItem("Move to group…");
        move.addActionListener(x -> moveSessionToProject(entry)); menu.add(move);
        menu.addSeparator();
        javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete session…");
        delete.addActionListener(x -> deleteSession(entry)); menu.add(delete);
        menu.addSeparator();
        javax.swing.JMenuItem newProject = new javax.swing.JMenuItem("New group…");
        newProject.addActionListener(x -> createProjectUI(null)); menu.add(newProject);
        return menu;
    }

    /**
     * The popup of a group header, shared by the right-click on the row and the group button
     * in the rail so the two paths cannot drift apart.
     */
    private javax.swing.JPopupMenu groupMenu(SessionEntry group) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        String projectId = group.header.projectId();
        if (projectId != null) {
            JMenuItem newSession = new JMenuItem("New session here");
            newSession.addActionListener(x -> createSession(projectId));
            JMenuItem props = new JMenuItem("Group settings…");
            props.addActionListener(x -> groupPropsUI(group));
            JMenuItem rename = new JMenuItem("Rename group…");
            rename.addActionListener(x -> renameProjectUI(group));
            JMenuItem remove = new JMenuItem("Delete group…");
            remove.addActionListener(x -> removeProjectUI(group));
            JMenuItem detachAll = new JMenuItem("Move all sessions out (detach)");
            detachAll.addActionListener(x -> detachProjectSessions(group));
            JMenuItem moveHere = new JMenuItem("Move current session here");
            moveHere.addActionListener(x -> {
                SessionEntry current = activeSessionEntry();
                if (current != null) {
                    moveSessionTo(current.id, projectId, group.header.title());
                }
            });
            menu.add(newSession); menu.addSeparator();
            menu.add(props); menu.add(rename); menu.add(detachAll); menu.add(remove);
            menu.addSeparator(); menu.add(moveHere);
        } else if (group.header.unknown()) {
            JMenuItem refresh = new JMenuItem("Refresh");
            refresh.addActionListener(x -> refreshProjectSnapshot());
            menu.add(refresh);
        } else {
            // the Default bucket: it is the absence of a project, so it has no row to
            // rename, delete or give settings — giving it a name creates the project and
            // adopts its rows. The settings entry is still offered because it routes to
            // exactly that naming step, then to the new group's form.
            JMenuItem newSession = new JMenuItem("New session here");
            newSession.addActionListener(x -> createSession(null));
            JMenuItem props = new JMenuItem("Group settings…");
            props.addActionListener(x -> groupPropsUI(group));
            JMenuItem adopt = new JMenuItem("Rename as a group…");
            adopt.addActionListener(x -> renameDefaultGroupUI());
            JMenuItem moveOut = new JMenuItem("Move selected session here");
            moveOut.addActionListener(x -> {
                SessionEntry selected = sessionList.getSelectedValue();
                SessionEntry subject = selected != null && !selected.isGroup() ? selected : activeSessionEntry();
                if (subject != null) moveSessionTo(subject.id, null, SessionGrouping.DEFAULT_GROUP);
            });
            menu.add(newSession); menu.addSeparator();
            menu.add(props); menu.add(adopt); menu.add(moveOut);
        }
        JMenuItem newProject = new JMenuItem("New group…");
        newProject.addActionListener(x -> createProjectUI(null));
        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(x -> refreshProjectSnapshot());
        menu.addSeparator(); menu.add(newProject); menu.add(refresh);
        return menu;
    }

    /** The group a menu or shortcut should act on: a picked header, or the one a picked session sits under. */
    private SessionEntry selectedGroupRow() {
        int index = sessionList.getSelectedIndex();
        SessionEntry entry = index < 0 ? null : sessionList.getModel().getElementAt(index);
        if (entry == null) return null;
        if (entry.isGroup()) return entry;
        net.thevpc.naru.desktop.model.SessionGrouping.Header header = headerAtOrAbove(index);
        return header == null ? null : SessionEntry.group(header);
    }

    private void showGroupMenuAt(java.awt.Component anchor) {
        SessionEntry group = selectedGroupRow();
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (group == null) {
            // Flat sidebar: the project read has not landed, so there is no header to act on.
            // The menu still has to be able to start the tree, and has to say why the groups
            // are missing when it could not.
            String error = projectReadError;
            if (error != null) {
                JMenuItem reason = new JMenuItem("No groups: " + error);
                reason.setEnabled(false);
                menu.add(reason);
                menu.addSeparator();
            }
            JMenuItem newProject = new JMenuItem("New group…");
            newProject.addActionListener(x -> createProjectUI(null));
            JMenuItem refresh = new JMenuItem("Refresh");
            refresh.addActionListener(x -> refreshProjectSnapshot());
            menu.add(newProject); menu.add(refresh);
        } else {
            menu = groupMenu(group);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    @Override
    protected void addImpl(Component comp, Object constraints, int index) {
        super.addImpl(comp, constraints, index);
        installResizeRecursive(comp);
    }

    private JPanel topBar() {
        HeaderBar shell = new HeaderBar(() -> maximized, this::toggleMaximize);
        JMenuBar menus = HeaderBar.washedMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem newSession = new JMenuItem("New session…");
        newSession.addActionListener(e -> createSession());
        JMenuItem close = new JMenuItem("Close session");
        close.addActionListener(e -> closeSession());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        file.add(newSession); file.add(close); file.addSeparator(); file.add(exit);
        JMenu view = new JMenu("View");
        JMenuItem conversation = new JMenuItem("Conversation"); conversation.addActionListener(e -> selectSideTab("Conversation"));
        JMenuItem log = new JMenuItem("Logs & trace"); log.addActionListener(e -> selectSideTab("Logs"));
        JMenuItem focusComposer = new JMenuItem("Focus composer"); focusComposer.setAccelerator(javax.swing.KeyStroke.getKeyStroke("control L")); focusComposer.addActionListener(e -> { if (input != null) input.focusInput(); });
        JMenuItem settingsPage = new JMenuItem("Settings…"); settingsPage.setAccelerator(javax.swing.KeyStroke.getKeyStroke("control COMMA")); settingsPage.addActionListener(e -> showSettings());
        view.add(conversation); view.add(log); view.addSeparator(); view.add(focusComposer); view.add(settingsPage);
        JMenu sessionMenu = new JMenu("Session");
        JMenuItem openSelected = new JMenuItem("Open selected session"); openSelected.addActionListener(e -> {
            SessionEntry selected = sessionList.getSelectedValue(); if (selected != null) activateSession(selected);
        });
        JMenuItem rename = new JMenuItem("Rename session…"); rename.addActionListener(e -> renameSelectedSession());
        JMenuItem closeCurrent = new JMenuItem("Close session"); closeCurrent.addActionListener(e -> closeSession());
        JMenuItem deleteSelected = new JMenuItem("Delete selected session…"); deleteSelected.addActionListener(e -> deleteSession(sessionList.getSelectedValue()));
        sessionMenu.add(openSelected); sessionMenu.add(rename); sessionMenu.add(closeCurrent); sessionMenu.add(deleteSelected);
        JMenu help = new JMenu("Help"); help.add(new JMenuItem("About NARU"));
        menus.add(file); menus.add(view); menus.add(sessionMenu); menus.add(help);
        // The mark leads, then the menus: the bar says what the window is before it says what you
        // can do in it, and the wash under it starts at the mark and fades away from it.
        JPanel leading = new JPanel(new BorderLayout(10, 0));
        leading.setOpaque(false);
        leading.add(new BrandMark(), BorderLayout.WEST);
        leading.add(menus, BorderLayout.CENTER);
        shell.add(leading, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 3));
        controls.setOpaque(false);
        // Each control keeps the hue of what it does to the window: the interface's own accent for
        // the settings page, amber for stepping out of the way, green for taking the screen, and
        // the palette's red for the one control that ends the run. They show only while the pointer
        // is on them, so none of them can be mistaken for a session's state dot.
        HoverTint settings = new HoverTint("⚙", UiPalette.accent(), "Settings");
        settings.addActionListener(e -> showSettings());
        HoverTint min = new HoverTint("—", UiPalette.PENDING, "Minimize");
        min.addActionListener(e -> setState(ICONIFIED));
        HoverTint max = new HoverTint("□", UiPalette.LIVE, "Maximize / restore");
        max.addActionListener(e -> toggleMaximize());
        HoverTint quit = new HoverTint("×", UiPalette.ATTENTION, "Close");
        quit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        // the four controls carry one glyph each: a margin-sized box is wide enough at the font the
        // window was built with and elides the glyph to "…" once the interface size grows
        net.thevpc.naru.desktop.ui.ActionButtons.glyphSized(settings, min, max, quit);
        for (JButton b : new JButton[]{settings, min, max, quit}) controls.add(b);
        shell.add(controls, BorderLayout.EAST);
        // The Centre of the bar is left to the shell itself: it is the drag handle, and
        // HeaderBar answers the pointer where its own chrome is not a control.
        return shell;
    }

    /**
     * Name the session the reader is in and paint its state next to it — in the status footer, the
     * row under the composer.
     * <p>
     * Every place that follows the active session — it opens, it is renamed, another one takes its
     * place — goes through here, so the dot can never be left showing the state of the session the
     * label stopped naming.
     */
    private void showActiveSession(String name, SessionEntry entry) {
        setTitle("NARU — " + name);
        if (statusBar != null) {
            statusBar.setSessionIdentity(name, entry == null ? null : entry.stateColor(),
                    entry != null && entry.isOpening());
        }
    }

    private void installLoadingOverlay() {
        loadingPanel = new JPanel(new java.awt.GridBagLayout());
        loadingPanel.setOpaque(true);
        JPanel card = new JPanel(new BorderLayout(12, 12));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)));
        loadingLabel = new JLabel("Loading…");
        JProgressBar progress = new JProgressBar(); progress.setIndeterminate(true); progress.setPreferredSize(new java.awt.Dimension(240, 8));
        card.add(loadingLabel, BorderLayout.NORTH); card.add(progress, BorderLayout.CENTER);
        loadingPanel.add(card); loadingPanel.setVisible(false); setGlassPane(loadingPanel);
    }

    private void showLoading(String message) { loadingLabel.setText(message); refreshLoadingPalette(); loadingPanel.setVisible(true); }
    private void hideLoading() { loadingPanel.setVisible(false); }

    /**
     * The session row's height. The cell draws two lines (name, then the created/state line), so a
     * constant 58px fits one interface size and clips the rest: at a larger interface size the
     * second line is cut off, at a smaller one the row is mostly empty. Derive it from the list's
     * own font so the row grows and shrinks with the interface size the way everything else does.
     */
    private void applySessionRowHeight() {
        if (sessionList == null) return;
        java.awt.Font f = sessionList.getFont();
        if (f == null) return;
        int line = sessionList.getFontMetrics(f).getHeight();
        sessionList.setFixedCellHeight(Math.max(line + 12, (int) Math.round(line * 2.7)));
        if (sessionRailPanel != null) {
            // the "＋ New" button sits on the same row as the "Sessions" title; a fixed 220px rail
            // clips it once the interface size grows, so the rail keeps pace with the font too
            sessionRailPanel.setPreferredSize(new java.awt.Dimension(
                    railWidth(), sessionRailPanel.getPreferredSize().height));
        }
    }

    /** The sessions sidebar's width for the current interface size. */
    private int railWidth() {
        java.awt.Font f = sessionList == null ? null : sessionList.getFont();
        if (f == null) f = getFont();
        if (f == null) return 220;
        java.awt.FontMetrics fm = sessionList != null
                ? sessionList.getFontMetrics(f)
                : getFontMetrics(f);
        if (fm == null) return 220;
        return Math.max(fm.getHeight() * 11, (int) Math.round(fm.stringWidth("Sessions") * 2.6) + 40);
    }

    /** Reapply the active look-and-feel to UI pieces that use custom renderers or a glass pane. */
    public void refreshThemePalette() {
        TextAreas.refreshThemeColors(getContentPane());
        java.awt.Color surface = javax.swing.UIManager.getColor("Panel.background");
        if (surface != null) { center.setBackground(surface); loadingPanel.setBackground(surface); }
        if (sessionList != null) {
            sessionList.updateUI();
            java.awt.Color listBg = javax.swing.UIManager.getColor("List.background");
            if (listBg != null) sessionList.setBackground(listBg);
        }
        if (sessionFilter != null) {
            sessionFilter.updateUI();
            java.awt.Color fieldBg = javax.swing.UIManager.getColor("TextField.background");
            if (fieldBg != null) sessionFilter.setBackground(fieldBg);
        }
        applySessionRowHeight();
        if (input != null) input.refreshThemePalette();
        if (taskPane != null) taskPane.refreshThemePalette();
        refreshLoadingPalette();
        repaint();
    }

    private void refreshLoadingPalette() {
        java.awt.Color surface = javax.swing.UIManager.getColor("Panel.background");
        java.awt.Color text = javax.swing.UIManager.getColor("Label.foreground");
        java.awt.Color accent = javax.swing.UIManager.getColor("Component.accentColor");
        if (surface != null) { loadingPanel.setBackground(surface); }
        if (text != null) loadingLabel.setForeground(text);
        if (accent != null) javax.swing.UIManager.put("ProgressBar.foreground", accent);
    }

    private void installNavigationKeys() {
        javax.swing.InputMap inputMap = getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap actions = getRootPane().getActionMap();
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("control L"), "focus-composer");
        actions.put("focus-composer", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { if (input != null) input.focusInput(); } });
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("control shift F"), "focus-session-filter");
        actions.put("focus-session-filter", new javax.swing.AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { sessionFilter.requestFocusInWindow(); sessionFilter.selectAll(); } });
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("F6"), "cycle-workspace-focus");
        actions.put("cycle-workspace-focus", new javax.swing.AbstractAction() {
            private int index;
            public void actionPerformed(java.awt.event.ActionEvent e) {
                java.awt.Component[] targets = {sessionFilter, sessionList, taskPane, transcript, input == null ? null : input.composer()};
                for (int n=0;n<targets.length;n++) { index=(index+1)%targets.length; if(targets[index]!=null && targets[index].isShowing() && targets[index].isEnabled()){ targets[index].requestFocusInWindow(); return; } }
            }
        });
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PLUS,java.awt.event.InputEvent.CTRL_DOWN_MASK),"font-increase");
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,java.awt.event.InputEvent.CTRL_DOWN_MASK|java.awt.event.InputEvent.SHIFT_DOWN_MASK),"font-increase");
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD,java.awt.event.InputEvent.CTRL_DOWN_MASK),"font-increase");
        actions.put("font-increase",new javax.swing.AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){NaruDesktop.setFontScale(NaruDesktop.fontScale()+10);}});
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,java.awt.event.InputEvent.CTRL_DOWN_MASK),"font-decrease");
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT,java.awt.event.InputEvent.CTRL_DOWN_MASK),"font-decrease");
        actions.put("font-decrease",new javax.swing.AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){NaruDesktop.setFontScale(NaruDesktop.fontScale()-10);}});
        inputMap.put(javax.swing.KeyStroke.getKeyStroke("control 0"),"font-reset");
        actions.put("font-reset",new javax.swing.AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){NaruDesktop.setFontScale(100);}});
    }

    private void renameSelectedSession() {
        SessionEntry entry = sessionList.getSelectedValue();
        if (entry == null || entry.model == null || entry.model.session() == null) return;
        String oldName = entry.model.session().name();
        String value=net.thevpc.naru.desktop.ui.DesktopDialog.promptText(this,"Rename session","Session name",oldName);
        if(value==null||value.isBlank()) return;
        String newName=value.trim(); showLoading("Saving session name…");
            new javax.swing.SwingWorker<Void,Void>() {
                protected Void doInBackground(){
                    // USER, not the titler's AUTO: an inline rename is a person choosing, and
                    // nothing automatic may come back later and overwrite it.
                    entry.model.session().rename(newName, net.thevpc.naru.api.agent.NaruSessionNameSource.USER).save();
                    return null;
                }
                protected void done(){hideLoading();try{get();rebuildSessionList();sessionList.repaint();showActiveSession(newName, entry);}catch(Exception ex){Throwable c=ex.getCause()==null?ex:ex.getCause();net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this,"Rename session",String.valueOf(c.getMessage()));}}
            }.execute();
    }

    /**
     * Ask a model for this session's name from its stored history, off the UI thread. The same
     * service {@code /session title} calls; a failure is a message, never a silent no-op.
     */
    private void regenerateSessionTitle(SessionEntry entry) {
        if (entry == null || entry.model == null || entry.model.session() == null) return;
        net.thevpc.naru.api.agent.NaruSession session = entry.model.session();
        showLoading("Generating a title…");
        new javax.swing.SwingWorker<String,Void>() {
            protected String doInBackground(){
                java.util.List<net.thevpc.naru.api.task.NaruTask> tasks = session.tasks();
                net.thevpc.naru.api.task.NaruTask transport =
                        session.findTask(session.foregroundTaskId()).orNull();
                if (transport == null && !tasks.isEmpty()) transport = tasks.get(0);
                if (transport == null) {
                    throw new IllegalStateException("this session has no conversation to title from");
                }
                net.thevpc.naru.api.title.NaruSessionTitler titler = session.registry()
                        .extension(net.thevpc.naru.api.title.NaruSessionTitler.EXTENSION,
                                net.thevpc.naru.api.title.NaruSessionTitler.class).orNull();
                if (titler == null) {
                    throw new IllegalStateException("no titling extension is installed");
                }
                return titler.regenerate(net.thevpc.naru.api.title.NaruTitleRequest
                        .excerpt(session, transport));
            }
            protected void done(){
                hideLoading();
                try {
                    get();
                    // the rename event refreshes the row; this only covers the case where the
                    // listener is not attached to this model
                    sessionList.repaint();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this, "Generate title",
                            String.valueOf(c.getMessage()));
                }
            }
        }.execute();
    }

    /**
     * Put the window at full screen, or back where it was.
     * <p>
     * Done by moving the bounds rather than through {@code setExtendedState(MAXIMIZED_BOTH)}: this
     * window is undecorated and draws its own chrome, and a maximize request is a favour from the
     * window manager — X11 asks it to set {@code _NET_WM_STATE} and XWayland, or any session with no
     * compliant manager, simply never answers. The frame then keeps reporting the state it was
     * given while its bounds stay exactly as they were, which reads as a dead button.
     */
    private void toggleMaximize() {
        if (maximized) {
            maximized = false;
            if (restoredBounds != null) setBounds(restoredBounds);
            return;
        }
        restoredBounds = getBounds();
        maximized = true;
        setBounds(screenWorkArea());
    }

    /** The screen this window is on, minus the strips the desktop reserves for its own panels. */
    private java.awt.Rectangle screenWorkArea() {
        java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
        java.awt.Rectangle screen = gc != null ? gc.getBounds()
                : new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        if (gc == null) return screen;
        java.awt.Insets reserved = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        screen.x += reserved.left;
        screen.y += reserved.top;
        screen.width = Math.max(1, screen.width - reserved.left - reserved.right);
        screen.height = Math.max(1, screen.height - reserved.top - reserved.bottom);
        return screen;
    }

    private void createSession() {
        createSession(null);
    }

    /**
     * Start a session. {@code projectId} is the group whose plus was clicked, or null for the
     * Default bucket: the engine copies it into the new session's record, so the row lands in
     * that group and stays there after a reopen.
     */
    private void createSession(String projectId) {
        NPath dir = projectDirectory == null
                ? NPath.of(System.getProperty("user.dir", ".")).toAbsolute() : projectDirectory;
        openProject(dir, null, net.thevpc.naru.api.store.NaruSessionScope.PRIVATE, true, projectId);
    }

    /**
     * Ask the first catalog load to open this saved session instead of just selecting the top row.
     * Passing null (the common case) leaves the window on the plain listing behaviour.
     */
    public void openSessionWhenCatalogLoads(String uuid) {
        openSessionUuid = (uuid == null || uuid.isBlank()) ? null : uuid.trim();
    }

    /**
     * Consume the requested reopen: the field is cleared on the first load that has rows, so a
     * later project switch is not held hostage by a uuid from the command line. A uuid that names
     * no row in this project is reported and dropped rather than silently opening someone else.
     */
    private SessionEntry takeRequestedSession() {
        if (openSessionUuid == null) return null;
        String uuid = openSessionUuid;
        openSessionUuid = null;
        for (SessionEntry e : sessions) {
            if (uuid.equals(e.id)) return e;
        }
        net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, "Open session",
                "No saved session " + uuid + " in " + projectDirectory + ". The listing shows this "
                        + "project's sessions instead.");
        return null;
    }

    /** Load durable rows without building or starting a session. */
    public void loadProjectCatalog(NPath dir) {
        projectDirectory = dir.toAbsolute();
        NaruDesktop.rememberProject(projectDirectory);
        catalogLoadedProjects.add(projectDirectory.toString());
        showLoading("Loading saved sessions…");
        new javax.swing.SwingWorker<List<net.thevpc.naru.api.agent.NaruResourceInfo>, Void>() {
            protected List<net.thevpc.naru.api.agent.NaruResourceInfo> doInBackground() {
                return catalog(projectDirectory).list();
            }
            protected void done() {
                hideLoading();
                try {
                    List<net.thevpc.naru.api.agent.NaruResourceInfo> saved = get();
                    sessions.clear();
                    for (var info : saved) {
                        var scope = info.getMode() == net.thevpc.naru.api.agent.NaruVisibility.PUBLIC
                                ? net.thevpc.naru.api.store.NaruSessionScope.PUBLIC
                                : net.thevpc.naru.api.store.NaruSessionScope.PRIVATE;
                        sessions.add(new SessionEntry(info.getUuid(), scope,
                                projectDirectory, info, null, null));
                    }
                    rebuildSessionList();
                    refreshProjectSnapshot();
                    if (sessions.isEmpty()) {
                        showActiveSession("No session", null);
                        addWelcome();
                    } else {
                        // A --session uuid from the command line opens that row rather than the
                        // first one in the listing: the same conversation the REPL resumes with
                        // -s, so a run started in either host continues in the other.
                        SessionEntry requested = takeRequestedSession();
                        if (requested == null) {
                            selectFirstSessionRow();
                        } else {
                            sessionList.setSelectedIndex(sessions.indexOf(requested));
                            activateSession(requested);
                        }
                    }
                } catch (Exception ex) {
                    addWelcome();
                    net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this,
                            "Load sessions", String.valueOf(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                }
            }
        }.execute();
    }

    /**
     * The saved-session catalog for a project, one per workspace.
     * <p>
     * Deliberately not a store opened here: a host that opens its own handle to the same
     * folder lists a session once per handle and then cannot delete the row it just read,
     * because the delete goes through a different handle than the listing did. The workspace
     * owns the one catalog, and this is the only door to it.
     */
    private static net.thevpc.naru.api.agent.NaruSessionStoreManager catalog(NPath dir) {
        return NaruDesktop.workspace(dir).sessionCatalog();
    }

    /** The group row's caption: a caret, the project's own glyph in its own hue, the name, the count. */
    private static String groupTitleHtml(SessionEntry group) {
        String title = htmlEscape(group.header.title());
        int n = group.header.count();
        String count = n > 0
                ? "<span style='color:" + UiPalette.css(UiPalette.muted()) + ";'> &middot; "
                + n + " session" + (n == 1 ? "" : "s") + "</span>"
                : "";
        String mark = "";
        net.thevpc.naru.desktop.model.SessionGrouping.Project project = group.header.project();
        if (project != null) {
            String symbol = net.thevpc.naru.desktop.ui.GroupPresets.symbolOf(project.icon());
            if (symbol != null) {
                java.awt.Color hue = net.thevpc.naru.desktop.ui.GroupPresets.colorOf(project.color());
                mark = "<span style='color:" + UiPalette.css(hue == null ? UiPalette.muted()
                        : UiPalette.readable(hue)) + ";'>" + symbol + "</span>&nbsp;&nbsp;";
            }
        }
        return "&#9656;&nbsp;&nbsp;" + mark + "<b>" + title + "</b>" + count;
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The group a dropped row targets: the header at or above {@code row} — dropping on a
     * session row means the group that row sits in — or null above the first header (the
     * flat, projectless listing has nowhere to move to).
     */
    private net.thevpc.naru.desktop.model.SessionGrouping.Header headerAtOrAbove(int row) {
        for (int i = Math.min(row, sessionList.getModel().getSize() - 1); i >= 0; i--) {
            SessionEntry candidate = sessionList.getModel().getElementAt(i);
            if (candidate.isGroup()) return candidate.header;
        }
        return null;
    }

    /** The sidebar row for the session this window is reading, or null. */
    private SessionEntry activeSessionEntry() {
        for (SessionEntry entry : sessions) {
            if (entry.model == session) return entry;
        }
        return null;
    }

    /**
     * The Default bucket is not a stored project, so naming it makes one: the same group form
     * opens, prefilled with the bucket's own title, and says how many sessions the new group is
     * about to take in. They move in the same off-EDT action that files the row.
     */
    private void renameDefaultGroupUI() {
        List<String> moveIds = defaultGroupSessionIds();
        String name = SessionGrouping.DEFAULT_GROUP;
        groupFormUI(new GroupFormSeed(null,
                "Rename group — " + moveIds.size() + " session(s) of "
                        + SessionGrouping.DEFAULT_GROUP + " move into it",
                name, NPath.of(suggestedProjectFolder(name)), null, null, null, moveIds));
    }

    /**
     * The group a row belongs to. The store answers for any session it has listed; a row
     * created moments ago has no uuid and no listing yet, so the group whose plus was clicked
     * is the only witness. A detach clears both, which is what puts the row back in Default.
     */
    private String projectOfRow(SessionEntry entry, ProjectSnapshot snap) {
        if (entry.id != null && snap != null) {
            String fromStore = snap.projectOf().get(entry.id);
            if (fromStore != null) return fromStore;
        }
        return entry.projectId;
    }

    /**
     * Every session that belongs to no project — the members of the Default bucket, filter
     * or no filter. A session the project read never reported counts as a member: it has no
     * project as far as the sidebar is concerned.
     */
    private List<String> defaultGroupSessionIds() {
        ProjectSnapshot snap = projectSnapshot;
        List<String> ids = new ArrayList<>();
        for (SessionEntry entry : sessions) {
            if (entry.id == null) continue;
            if (projectOfRow(entry, snap) == null) ids.add(entry.id);
        }
        return ids;
    }

    /**
     * Create a group: the shared form, empty but for a suggested folder. The two prompts of the
     * old flow (a name, then a folder) are rows of one form now, so a group can be given its
     * glyph and hue at the moment it is invented rather than in a second pass.
     * {@code initialName} pre-fills the name row, which is how the Default bucket offers its own
     * title as the starting point.
     */
    private void createProjectUI(String initialName) {
        String name = initialName == null ? "" : initialName;
        groupFormUI(new GroupFormSeed(null, "New group", name,
                NPath.of(suggestedProjectFolder(name)), null, null, null, List.of()));
    }

    private String suggestedProjectFolder(String name) {
        return projectDirectory == null ? System.getProperty("user.dir", ".")
                : projectDirectory.resolve(name).toString();
    }

    /**
     * The group's own settings. Nothing lives in a separate place from creation any more: the
     * same form opens on the stored row, and OK patches that row — folder included, since a
     * group that moved on disk should be able to say so.
     */
    private void groupPropsUI(SessionEntry group) {
        net.thevpc.naru.desktop.model.SessionGrouping.Project row = group.header.project();
        if (row == null) {
            // the Default bucket owns no project row, so its settings are the ones that
            // create one: naming it is what turns the bucket into a group
            renameDefaultGroupUI();
            return;
        }
        groupFormUI(new GroupFormSeed(row.id(), "Group settings — " + row.name(), row.name(),
                row.folder(), row.icon(), row.color(), row.description(), List.of()));
    }

    /**
     * What the group form starts with, and what its OK button does: {@code projectId == null}
     * files a new row, anything else patches that row. {@code adoptSessions} belongs to the
     * create path only — it is how naming the Default bucket pulls the bucket inside.
     */
    private record GroupFormSeed(String projectId, String title, String name, NPath folder,
                                 String icon, String color, String description,
                                 List<String> adoptSessions) {
        boolean creating() {
            return projectId == null;
        }
    }

    /**
     * The group form's editable rows, gathered so the folder probe has one target to write into
     * instead of six captured variables.
     */
    private static final class GroupFormFields {
        JTextField name;
        JComboBox<String> icon;
        JComboBox<String> color;
        JTextArea description;
        JLabel status;

        void apply(net.thevpc.naru.api.project.NaruProject row) {
            name.setText(row.name());
            icon.setSelectedItem(row.icon());
            color.setSelectedItem(row.color());
            description.setText(row.description() == null ? "" : row.description());
        }
    }

    /**
     * The one group form: name, folder, glyph, hue, description.
     *
     * <p>The folder is not just a path to file under the row: a folder that already holds a NARU
     * project store describes a group, and reading it is what makes a checkout on disk come back
     * with its own name and paint. Both doors read it the same way, as the folder row settles —
     * browsing somewhere is answering the question "what group lives here?", not editing props by
     * hand. Nothing is written until OK, so a browse that turns the form into someone else's group
     * is one Cancel away from being nothing at all.
     */
    private void groupFormUI(GroupFormSeed seed) {
        GroupFormFields fields = new GroupFormFields();
        fields.name = new JTextField(seed.name(), 28);
        JTextField folder = new JTextField(
                seed.folder() == null ? "" : seed.folder().toString(), 24);
        fields.icon = groupIconCombo(seed.icon());
        fields.color = groupColorCombo(seed.color());
        fields.description = new JTextArea(seed.description(), 3, 28);
        fields.description.setLineWrap(true);
        fields.description.setWrapStyleWord(true);
        fields.status = new JLabel(" ");
        fields.status.setForeground(UiPalette.muted());

        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.setOpaque(false);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(folder);
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(folder.getText());
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Group folder");
            if (chooser.showOpenDialog(owner) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            folder.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);

        // the typed path is read as it settles, in both doors: a pause, not a keystroke, because
        // half a path is a folder that exists nowhere and every keystroke would queue a probe
        javax.swing.Timer probeTimer = new javax.swing.Timer(450,
                e -> probeGroupFolder(folder.getText(), fields));
        probeTimer.setRepeats(false);
        javax.swing.event.DocumentListener spoken = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { probeTimer.restart(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { probeTimer.restart(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { probeTimer.restart(); }
        };
        // added after the initial text, so opening the form on a stored row does not re-read it
        folder.getDocument().addDocumentListener(spoken);

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.anchor = java.awt.GridBagConstraints.NORTHWEST;
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Name"), c);
        c.gridx = 1; c.weightx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(fields.name, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = java.awt.GridBagConstraints.NONE;
        form.add(new JLabel("Folder"), c);
        c.gridx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(folderRow, c);
        c.gridx = 0; c.gridy = 2;
        form.add(new JLabel("Icon"), c);
        c.gridx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(fields.icon, c);
        c.gridx = 0; c.gridy = 3;
        form.add(new JLabel("Colour"), c);
        c.gridx = 1; c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(fields.color, c);
        c.gridx = 0; c.gridy = 4; c.weightx = 0; c.weighty = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        form.add(new JLabel("Description"), c);
        c.gridx = 1; c.weightx = 1; c.weighty = 1; c.fill = java.awt.GridBagConstraints.BOTH;
        form.add(new JScrollPane(fields.description), c);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2; c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        form.add(fields.status, c);

        if (!net.thevpc.naru.desktop.ui.DesktopDialog.confirmComponent(this, seed.title(), form)) {
            return;
        }
        final String fname = fields.name.getText() == null
                ? "" : fields.name.getText().trim();
        if (fname.isEmpty()) {
            net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, seed.title(),
                    "A group needs a name. Nothing was changed.");
            return;
        }
        final String ffolder = folder.getText() == null ? "" : folder.getText().trim();
        if (ffolder.isEmpty()) {
            net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, seed.title(),
                    "A group needs a folder: it is what its new sessions start in. Nothing was changed.");
            return;
        }
        final String ficon = (String) fields.icon.getSelectedItem();
        final String fcolor = (String) fields.color.getSelectedItem();
        final String fdescription = fields.description.getText().trim();
        if (seed.creating()) {
            // no draft-taking create in the service, so a new row is filed and then painted:
            // two writes, and the second one carries the version the first left behind
            final java.util.concurrent.atomic.AtomicReference<String> warnings =
                    new java.util.concurrent.atomic.AtomicReference<>();
            runProjectAction("Add group", () -> {
                var service = projectService();
                var added = service.add(fname, NPath.of(ffolder), null);
                if (!added.warnings().isEmpty()) {
                    warnings.set(String.join("\n", added.warnings()));
                }
                var row = added.value();
                service.edit(row.id(), row.version(), groupPatch(fname, ffolder, ficon, fcolor, fdescription));
                if (!seed.adoptSessions().isEmpty()) {
                    service.moveSessions(seed.adoptSessions(), row.id());
                }
            }, () -> {
                if (warnings.get() != null) {
                    net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this,
                            "Add group", warnings.get());
                }
            });
            return;
        }
        runProjectAction("Save group settings", () -> {
            var service = projectService();
            var found = service.find(seed.projectId()).get();
            service.edit(found.id(), found.version(),
                    groupPatch(fname, ffolder, ficon, fcolor, fdescription));
        }, null);
    }

    /**
     * The patch the form writes. Every setter runs on every save — never a clear-or-unset branch:
     * a form that returns "no icon" means the icon should leave the row, which unset would
     * silently keep.
     */
    private static net.thevpc.naru.api.project.NaruProjectPatch groupPatch(
            String name, String folder, String icon, String color, String description) {
        return net.thevpc.naru.api.project.NaruProjectPatch.of()
                .name(name)
                .folder(NPath.of(folder))
                .icon(icon == null || icon.isBlank() ? null : icon)
                .color(color == null || color.isBlank() ? null : color)
                .description(description.isEmpty() ? null : description);
    }

    /** The preset glyph picker; the row stores the preset id, not the symbol it renders. */
    private JComboBox<String> groupIconCombo(String selected) {
        JComboBox<String> icon = new JComboBox<>();
        icon.addItem(null);
        for (net.thevpc.naru.desktop.ui.GroupPresets.Glyph g
                : net.thevpc.naru.desktop.ui.GroupPresets.glyphs()) {
            icon.addItem(g.id());
        }
        icon.setSelectedItem(selected);
        icon.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                                                                  int index, boolean isSelected,
                                                                  boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String id = (String) value;
                String symbol = net.thevpc.naru.desktop.ui.GroupPresets.symbolOf(id);
                setText((symbol == null ? "" : symbol + "  ")
                        + net.thevpc.naru.desktop.ui.GroupPresets.labelOf(id));
                return this;
            }
        });
        return icon;
    }

    /** The preset hue picker; the row stores a literal hex, so another host paints it too. */
    private JComboBox<String> groupColorCombo(String selected) {
        JComboBox<String> color = new JComboBox<>();
        color.addItem(null);
        for (net.thevpc.naru.desktop.ui.GroupPresets.Swatch s
                : net.thevpc.naru.desktop.ui.GroupPresets.swatches()) {
            color.addItem(s.hex());
        }
        color.setSelectedItem(selected);
        color.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
                                                                  int index, boolean isSelected,
                                                                  boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String hex = (String) value;
                java.awt.Color hue = net.thevpc.naru.desktop.ui.GroupPresets.colorOf(hex);
                setIcon(hue == null ? null
                        : new net.thevpc.naru.desktop.ui.GroupPresets.ColorIcon(hue, 12, 12));
                setText(net.thevpc.naru.desktop.ui.GroupPresets.colorLabel(hex));
                return this;
            }
        });
        return color;
    }

    /**
     * Read the group a folder already holds, off the event thread, and say what was found. The
     * fields take what the folder says: the folder row is the one the user is pointing at, so the
     * group living there is the answer — Cancel still leaves the stored row untouched.
     */
    private void probeGroupFolder(String folderText, GroupFormFields fields) {
        final String text = folderText == null ? "" : folderText.trim();
        if (text.isEmpty() || projectDirectory == null) {
            // the loader that can see the store extensions belongs to an open workspace, and a
            // form with no folder typed yet has nothing to read
            fields.status.setText(" ");
            return;
        }
        fields.status.setText("Reading the folder…");
        final NPath home = NPath.of(text);
        final NClassLoader loader = NaruDesktop.workspace(projectDirectory).classLoader();
        new javax.swing.SwingWorker<net.thevpc.naru.api.project.NaruProject, Void>() {
            private String message;

            @Override
            protected net.thevpc.naru.api.project.NaruProject doInBackground() {
                try {
                    net.thevpc.naru.api.project.NaruProject found = readGroupFolder(home, loader);
                    message = found == null
                            ? "No group store in this folder yet."
                            : "Loaded “" + found.name() + "” from the store in this folder.";
                    return found;
                } catch (Exception ex) {
                    String detail = ex.getMessage() == null
                            ? ex.getClass().getSimpleName() : ex.getMessage();
                    message = "This folder could not be read: " + detail;
                    return null;
                }
            }

            @Override
            protected void done() {
                net.thevpc.naru.api.project.NaruProject found = null;
                try {
                    found = get();
                } catch (Exception ignored) {
                    // doInBackground already turned every failure into a status line
                }
                fields.status.setText(message);
                if (found != null) fields.apply(found);
            }
        }.execute();
    }

    /**
     * The newest group {@code home} describes, or null when it holds no store. A row whose own
     * folder is this directory answers first: that is the group this folder was made for.
     */
    private net.thevpc.naru.api.project.NaruProject readGroupFolder(
            NPath home, NClassLoader loader) {
        net.thevpc.naru.api.project.NaruProjectStoreConfig config =
                new net.thevpc.naru.api.project.NaruProjectStoreConfig(home, null);
        net.thevpc.naru.api.project.NaruProjectStore store =
                net.thevpc.naru.api.project.NaruProjectStore.open(config, null, loader);
        try {
            java.util.List<net.thevpc.naru.api.project.NaruProject> rows = store.findByQuery(
                    new net.thevpc.naru.api.project.NaruProjectQuery(
                            null, null, net.thevpc.naru.api.store.NaruUserFilter.any(), null, 50))
                    .items();
            for (net.thevpc.naru.api.project.NaruProject row : rows) {
                if (row.folder() != null && row.folder().equals(home)) return row;
            }
            return rows.size() == 1 ? rows.get(0) : null;
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }
    }


    private void renameProjectUI(SessionEntry group) {
        if (group.header.unknown()) return;
        if (group.header.projectId() == null) {
            renameDefaultGroupUI();
            return;
        }
        String name = net.thevpc.naru.desktop.ui.DesktopDialog.promptText(this, "Rename group",
                "New group name", group.header.title());
        if (name == null || name.isBlank() || name.equals(group.header.title())) return;
        final String newName = name.trim();
        runProjectAction("Rename group", () -> {
            var service = projectService();
            var current = service.find(group.header.projectId()).get();
            service.edit(current.id(), current.version(),
                    net.thevpc.naru.api.project.NaruProjectPatch.of().name(newName));
        }, null);
    }

    private void detachProjectSessions(SessionEntry group) {
        // rows this window created carry their group themselves, so they leave it here too;
        // the listed ones follow the store once the refresh after this action lands
        for (SessionEntry entry : sessions) {
            if (group.header.projectId() != null && group.header.projectId().equals(entry.projectId)) {
                entry.projectId = null;
            }
        }
        runProjectAction("Detach sessions", () -> {
            var service = projectService();
            ProjectSnapshot snap = projectSnapshot;
            if (snap == null) return;
            List<String> ids = new ArrayList<>();
            snap.projectOf().forEach((uuid, pid) -> {
                if (pid.equals(group.header.projectId())) ids.add(uuid);
            });
            if (!ids.isEmpty()) service.moveSessions(ids, net.thevpc.naru.api.project.NaruProjectService.NO_PROJECT);
        }, null);
    }

    private void removeProjectUI(SessionEntry group) {
        ProjectSnapshot snap = projectSnapshot;
        int memberCount = 0;
        if (snap != null) {
            for (String pid : snap.projectOf().values()) {
                if (pid.equals(group.header.projectId())) memberCount++;
            }
        }
        Object choice;
        if (memberCount == 0) {
            if (!net.thevpc.naru.desktop.ui.DesktopDialog.confirm(this, "Delete group",
                    "Delete the group “" + group.header.title() + "”?\nIts sessions keep working; they simply belong to no group.",
                    "Delete")) return;
            choice = net.thevpc.naru.api.project.NaruProjectDeletePolicy.REJECT;
        } else {
            choice = javax.swing.JOptionPane.showInputDialog(this,
                    "This group still has " + memberCount + " session(s). What should happen to them?",
                    "Delete group", javax.swing.JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{"Detach — keep the sessions, they move to “" + SessionGrouping.DEFAULT_GROUP + "”",
                            "Cascade — delete the sessions with the group",
                            "Cancel"},
                    "Detach — keep the sessions, they move to “" + SessionGrouping.DEFAULT_GROUP + "”");
            if (choice == null || String.valueOf(choice).startsWith("Cancel")) return;
            boolean cascade = String.valueOf(choice).startsWith("Cascade");
            if (cascade) {
                if (!net.thevpc.naru.desktop.ui.DesktopDialog.confirm(this, "Delete group — cascade",
                        "Cascade deletes every session of “" + group.header.title() + "” — conversations, tasks and history — and then the group.",
                        "Delete everything")) return;
            }
            choice = cascade ? net.thevpc.naru.api.project.NaruProjectDeletePolicy.CASCADE
                    : net.thevpc.naru.api.project.NaruProjectDeletePolicy.DETACH;
        }
        final net.thevpc.naru.api.project.NaruProjectDeletePolicy policy =
                (net.thevpc.naru.api.project.NaruProjectDeletePolicy) choice;
        runProjectAction("Delete group", () -> {
            var service = projectService();
            var current = service.find(group.header.projectId()).get();
            var report = service.remove(current.id(), current.version(), policy);
            if (!report.projectDeleted()) {
                throw new IllegalStateException("partial removal: " + report.failedSessions().size()
                        + " session(s) could not be moved out of the way; the project was kept");
            }
        }, null);
    }

    private void moveSessionToProject(SessionEntry entry) {
        ProjectSnapshot snap = projectSnapshot;
        if (snap == null) {
            refreshProjectSnapshot();
            return;
        }
        String currentId = snap.projectOf().get(entry.id);
        java.util.List<net.thevpc.naru.desktop.model.SessionGrouping.Project> projects = snap.projects();
        String[] labels = new String[projects.size() + 1];
        labels[0] = SessionGrouping.DEFAULT_GROUP;
        for (int i = 0; i < projects.size(); i++) {
            labels[i + 1] = projects.get(i).name();
        }
        int selectedLabel = 0;
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id().equals(currentId)) selectedLabel = i + 1;
        }
        Object choice = javax.swing.JOptionPane.showInputDialog(this,
                "Move “" + entry + "” to which group?", "Move to group",
                javax.swing.JOptionPane.QUESTION_MESSAGE, null, labels, labels[selectedLabel]);
        if (choice == null) return;
        // identity, not equals: a project may legitimately be named "Default", and then the
        // same caption is two different targets in this list
        int idx = -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == choice) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx == selectedLabel) return;
        moveSessionTo(entry.id, idx <= 0 ? null : projects.get(idx - 1).id(), String.valueOf(choice));
    }

    /** The move is a projectId change only: directories, storage and content never move. */
    private void moveSessionTo(String sessionId, String targetProjectId, String targetLabel) {
        // the row keeps its group as its own fact, so it has to follow the move now: waiting
        // for the store listing would show the session under Default for a refresh cycle
        for (SessionEntry entry : sessions) {
            if (sessionId != null && sessionId.equals(entry.id)) entry.projectId = targetProjectId;
        }
        runProjectAction("Move session", () -> {
            var report = projectService().moveSession(sessionId,
                    targetProjectId == null ? net.thevpc.naru.api.project.NaruProjectService.NO_PROJECT : targetProjectId);
            if (!report.failed().isEmpty()) {
                throw new IllegalStateException(String.join("\n", report.failed().values()));
            }
        }, null);
    }

    /**
     * Runs a project-store action off the EDT and refreshes the sidebar when it lands;
     * failures surface as a message rather than a stack trace into the console.
     */
    private void runProjectAction(String title, Runnable action, Runnable onSuccess) {
        if (projectService() == null) {
            net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, title,
                    projectDirectory == null
                            ? "No workspace is open yet."
                            : "This workspace provides no project store, so groups cannot be changed.");
            return;
        }
        new javax.swing.SwingWorker<Void, Void>() {
            private Exception failure;

            @Override
            protected Void doInBackground() {
                try {
                    action.run();
                } catch (Exception e) {
                    failure = e;
                }
                return null;
            }

            @Override
            protected void done() {
                if (failure != null) {
                    net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this, title,
                            String.valueOf(failure.getMessage()));
                } else if (onSuccess != null) {
                    onSuccess.run();
                }
                refreshProjectSnapshot();
            }
        }.execute();
    }

    /**
     * Resize by dragging the window's own edges, since an undecorated window has no native frame to
     * grab. The listener sits on the frame, so it sees every mouse event the children propagate up
     * and must never claim one that belongs to a child: the edge is measured from the event's
     * component into this window's coordinates rather than trusting its raw point, and a press on a
     * divider is left to that divider.
     */
    private void installResizeSupport() {
        if (!resizeListenerInstalled) {
            resizeListenerInstalled = true;
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(resizeAWTListener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
    }

    private void installResizeRecursive(Component c) {
        // Resize is handled globally via an AWTEventListener; nothing to install per component.
    }

    private Cursor resizeCursor(int d) {
        return Cursor.getPredefinedCursor(switch (d) {
            case 1 -> Cursor.W_RESIZE_CURSOR;
            case 2 -> Cursor.E_RESIZE_CURSOR;
            case 4 -> Cursor.N_RESIZE_CURSOR;
            case 8 -> Cursor.S_RESIZE_CURSOR;
            case 5 -> Cursor.NW_RESIZE_CURSOR;
            case 6 -> Cursor.NE_RESIZE_CURSOR;
            case 9 -> Cursor.SW_RESIZE_CURSOR;
            case 10 -> Cursor.SE_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        });
    }

    private boolean isResizeCursor(Cursor c) {
        if (c == null) return false;
        int t = c.getType();
        return t == Cursor.W_RESIZE_CURSOR || t == Cursor.E_RESIZE_CURSOR
                || t == Cursor.N_RESIZE_CURSOR || t == Cursor.S_RESIZE_CURSOR
                || t == Cursor.NW_RESIZE_CURSOR || t == Cursor.NE_RESIZE_CURSOR
                || t == Cursor.SW_RESIZE_CURSOR || t == Cursor.SE_RESIZE_CURSOR;
    }

    /** The edge the pointer is on, as the bits {@code 1}=west {@code 2}=east {@code 4}=north {@code 8}=south. */
    private int resizeDirection(java.awt.Point p) {
        int d = 0;
        if (p.x < EDGE) d |= 1; else if (p.x > getWidth() - EDGE) d |= 2;
        if (p.y < EDGE) d |= 4; else if (p.y > getHeight() - EDGE) d |= 8;
        return d;
    }

    private void applyResizeCursor(int d) {
        Cursor cursor = Cursor.getPredefinedCursor(switch (d) {
            case 1 -> Cursor.W_RESIZE_CURSOR;
            case 2 -> Cursor.E_RESIZE_CURSOR;
            case 4 -> Cursor.N_RESIZE_CURSOR;
            case 8 -> Cursor.S_RESIZE_CURSOR;
            case 5 -> Cursor.NW_RESIZE_CURSOR;
            case 6 -> Cursor.NE_RESIZE_CURSOR;
            case 9 -> Cursor.SW_RESIZE_CURSOR;
            case 10 -> Cursor.SE_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        });
        // Setting a frame cursor is a request to the display every time, so only ask when it changes.
        if (!cursor.equals(appliedCursor)) {
            appliedCursor = cursor;
            setCursor(cursor);
        }
    }

    private void addWelcome() {
        JPanel p = new JPanel(new java.awt.GridBagLayout());
        JPanel card = new JPanel(new BorderLayout(0, 16)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = javax.swing.UIManager.getColor("TextArea.background");
                if (bg == null) bg = getBackground();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                Color border = javax.swing.UIManager.getColor("Component.borderColor");
                if (border != null) {
                    g2.setColor(border);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));

        // The card sizes from its content instead of a fixed box: a hard-coded preferred size
        // clipped this text as soon as a HiDPI laptop or a font scale above 100% made it taller
        // than the box. Swing's HTML engine only wraps a block that declares a width, so the
        // paragraphs say how wide they are and the card grows around them.
        int textWidth = 380;
        JLabel desc = new JLabel("<html><div style='width:" + textWidth + "px;line-height:1.5;color:#6B7280;'>"
                + "A high-performance desktop and terminal environment for agentic coding, "
                + "multi-agent coordination, and local &amp; cloud AI workflows.<br><br>"
                + "Get started by launching a new interactive session or choosing an existing one from the sidebar."
                + "</div></html>");
        net.thevpc.naru.desktop.ui.UiFonts.apply(desc, net.thevpc.naru.desktop.ui.UiFonts.BODY,
                java.awt.Font.PLAIN);
        JLabel title = new JLabel("✨ Welcome to NARU");
        net.thevpc.naru.desktop.ui.UiFonts.apply(title, net.thevpc.naru.desktop.ui.UiFonts.SUBTITLE,
                java.awt.Font.BOLD);
        Color accent = javax.swing.UIManager.getColor("Component.accentColor");
        if (accent != null) title.setForeground(accent);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        JButton create = new JButton("＋ New Session");
        net.thevpc.naru.desktop.ui.UiFonts.apply(create, net.thevpc.naru.desktop.ui.UiFonts.BODY,
                java.awt.Font.BOLD);
        create.putClientProperty("JButton.buttonType", "roundRect");
        create.addActionListener(e -> createSession());

        JButton settings = new JButton("⚙ Settings");
        settings.putClientProperty("JButton.buttonType", "roundRect");
        settings.addActionListener(e -> showSettings());

        actions.add(create);
        actions.add(settings);

        JLabel tip = new JLabel("<html><div style='width:" + textWidth + "px;font-size:"
                + net.thevpc.naru.desktop.ui.UiFonts.pointSize(net.thevpc.naru.desktop.ui.UiFonts.CAPTION)
                + "pt;color:#9CA3AF;'>Shortcuts: <b>Ctrl+L</b> Focus input · <b>Ctrl+,</b> Settings · <b>F6</b> Cycle panes</div></html>");

        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        bottom.setOpaque(false);
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(tip, BorderLayout.SOUTH);

        card.add(title, BorderLayout.NORTH);
        card.add(desc, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);

        p.add(card);
        center.removeAll();
        // The rail rides along even when no session is open: a group row is the door to the
        // first session, and a welcome page without it leaves that door unreachable on exactly
        // the workspace where the user needs it.
        JSplitPane railAndWelcome = SplitPanes.horizontal(sessionRail(), p);
        railAndWelcome.setResizeWeight(0.24);
        center.add(railAndWelcome, BorderLayout.CENTER);
        center.revalidate();
        center.repaint();
    }

    /** Close whatever is open and bind a fresh session to {@code dir}. */
    public void openProject(NPath dir) {
        openProject(dir, null);
    }

    private void openProject(NPath dir, String restoreId) {
        openProject(dir, restoreId, net.thevpc.naru.api.store.NaruSessionScope.PRIVATE);
    }

    private void openProject(NPath dir, String restoreId, net.thevpc.naru.api.store.NaruSessionScope restoreScope) {
        openProject(dir, restoreId, restoreScope, true);
    }

    private void openProject(NPath dir, String restoreId, net.thevpc.naru.api.store.NaruSessionScope restoreScope,
                             boolean selectOnCreate) {
        openProject(dir, restoreId, restoreScope, selectOnCreate, null);
    }

    /**
     * @param projectId the group to create the new session under, null for a standalone one.
     *                  Ignored on a restore, whose saved row already names its group.
     */
    private void openProject(NPath dir, String restoreId, net.thevpc.naru.api.store.NaruSessionScope restoreScope,
                             boolean selectOnCreate, String projectId) {
        SwingUtilities.invokeLater(() -> {
            host = new InteractionHost();
            // the queue drains from the first frame, so whatever the engine writes while the
            // workspace is still resolving shows up instead of being buffered behind the boot
            host.startFlushTimer();
            NaruSessionModel created = new NaruSessionModel(() -> NaruDesktop.workspace(dir), dir, host,
                    restoreId, restoreScope, restoreId == null ? projectId : null);
            SessionEntry entry = new SessionEntry(restoreId, restoreScope, dir,
                    (net.thevpc.naru.api.agent.NaruResourceInfo) null, created, host);
            entry.projectId = restoreId == null ? projectId : null;
            sessions.add(entry);
            rebuildSessionList();
            if (selectOnCreate) sessionList.setSelectedValue(entry, true);
            created.start();
            if (restoreId == null && catalogLoadedProjects.add(dir.toString())) {
                // The workspace owns a single catalog handle for the project. Always list
                // through it: a fresh store handle per read is what created duplicate sidebar
                // rows and made a deleted row appear to not exist.
                Timer catalogPoll = new Timer(250, null);
                catalogPoll.addActionListener(ev -> {
                    if (created.session() == null) return;
                    catalogPoll.stop();
                    // Session stores may touch disk, initialize a database, or scan a large
                    // catalog. Never do that from a Swing timer: the desktop must remain usable
                    // while durable sessions are being discovered.
                    new javax.swing.SwingWorker<List<net.thevpc.naru.api.agent.NaruResourceInfo>, Void>() {
                        @Override
                        protected List<net.thevpc.naru.api.agent.NaruResourceInfo> doInBackground() {
                            return catalog(dir).list();
                        }

                        @Override
                        protected void done() {
                            try {
                                List<net.thevpc.naru.api.agent.NaruResourceInfo> saved = get();
                                java.util.Set<String> known = new java.util.HashSet<>();
                                for (SessionEntry current : sessions) if (current.id != null) known.add(current.id);
                                for (var info : saved) {
                                    if (known.add(info.getUuid())) {
                                        var scope = info.getMode() == net.thevpc.naru.api.agent.NaruVisibility.PUBLIC
                                                ? net.thevpc.naru.api.store.NaruSessionScope.PUBLIC
                                                : net.thevpc.naru.api.store.NaruSessionScope.PRIVATE;
                                        // Saved sessions are lightweight rows until selected. Eagerly
                                        // booting the whole catalog created dozens of session threads
                                        // that all waited for Swing to finish rebuilding this list.
                                        sessions.add(new SessionEntry(info.getUuid(),
                                                scope, dir, info, null, null));
                                    }
                                }
                                rebuildSessionList();
                                refreshProjectSnapshot();
                                if (!saved.isEmpty()) SwingUtilities.invokeLater(MainWindow.this::selectFirstSessionRow);
                            } catch (Exception ex) {
                                created.notifyUser("Could not list saved sessions: "
                                        + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                            }
                        }
                    }.execute();
                });
                catalogPoll.start();
            }
        });
    }

    /**
     * Focus the first real session row, skipping any group header above it. Index 0 is a
     * header as soon as the sidebar groups, and a header cannot be opened or typed into.
     */
    private void selectFirstSessionRow() {
        DefaultListModel<SessionEntry> model = sessionRows;
        for (int i = 0; i < model.size(); i++) {
            if (!model.get(i).isGroup()) {
                sessionList.setSelectedIndex(i);
                return;
            }
        }
    }

    private void rebuildSessionList() {
        SessionEntry selected = sessionList.getSelectedValue();
        DefaultListModel<SessionEntry> replacement = new DefaultListModel<>();
        String query = sessionFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<SessionEntry> visible = new ArrayList<>();
        for (SessionEntry entry : sessions) {
            if (query.isEmpty() || entry.toString().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                visible.add(entry);
            }
        }
        ProjectSnapshot snap = projectSnapshot;
        if (snap == null) {
            // no snapshot yet: either no workspace is open or the project read failed. The
            // flat catalog is the honest answer here — the window does not yet know which
            // project each session names, and inventing a Default group over rows that may
            // all have one would be a lie the sidebar has to take back a second later.
            for (SessionEntry entry : visible) replacement.addElement(entry);
        } else {
            net.thevpc.naru.desktop.model.SessionGrouping.Header last = null;
            for (var line : net.thevpc.naru.desktop.model.SessionGrouping.layout(
                    snap.projects(), visible, e -> projectOfRow(e, snap))) {
                if (line.item() == null && !query.isEmpty()) continue; // empty group while filtering
                if (line.header() != last) {
                    replacement.addElement(SessionEntry.group(line.header()));
                    last = line.header();
                }
                if (line.item() != null) replacement.addElement(line.item());
            }
        }
        // Populate off-list, then publish once. DefaultListModel emits one event per insertion;
        // BasicListUI recomputes renderer sizes on every event, making a large durable catalog an
        // O(n²) EDT task (the thread dump showed it stuck in updateLayoutState here).
        sessionRows = replacement;
        sessionList.setModel(replacement);
        SessionEntry keep = selected != null && !selected.isGroup() ? selected : null;
        if (keep == null) {
            for (int i = 0; i < replacement.size(); i++) {
                if (replacement.get(i).model == session) {
                    keep = replacement.get(i);
                    break;
                }
            }
        }
        if (keep != null) {
            int index = replacement.indexOf(keep);
            if (index >= 0) sessionList.setSelectedIndex(index);
        }
    }

    // ── project grouping ─────────────────────────────────────────────────────────

    /**
     * The sidebar's project view: the tree's project rows in service order, and which
     * project each session header names. Refreshed off the EDT — it is a scan of the
     * session headers — and consumed by {@link #rebuildSessionList()}.
     */
    private record ProjectSnapshot(
            java.util.List<net.thevpc.naru.desktop.model.SessionGrouping.Project> projects,
            java.util.Map<String, String> projectOf) {
    }

    private volatile ProjectSnapshot projectSnapshot;
    /**
     * Why the project read has never succeeded, or null. The sidebar degrades to a flat list
     * in that case, which looks like a design choice unless the reason is somewhere the user
     * can read it.
     */
    private volatile String projectReadError;
    /** Workspaces whose project service already carries a refresh listener from this window. */
    private final java.util.Set<String> projectListenersOwned = new java.util.HashSet<>();

    private net.thevpc.naru.api.project.NaruProjectService projectService() {
        if (projectDirectory == null) return null;
        return projectServiceAt(projectDirectory);
    }

    /**
     * The project service of one directory's workspace, or null when the feature is not
     * installed. Projects left the kernel, so a desktop running without the naru-projects
     * module on that workspace's classpath has no tree to show; every group action then
     * reports "no workspace is open yet" rather than throwing inside a menu listener.
     */
    private net.thevpc.naru.api.project.NaruProjectService projectServiceAt(NPath dir) {
        return NaruDesktop.workspace(dir).extension(
                net.thevpc.naru.api.project.NaruProjectService.EXTENSION,
                net.thevpc.naru.api.project.NaruProjectService.class).orNull();
    }

    private void refreshProjectSnapshot() {
        if (projectDirectory == null) return;
        final NPath dir = projectDirectory;
        new javax.swing.SwingWorker<ProjectSnapshot, Void>() {
            @Override
            protected ProjectSnapshot doInBackground() {
                net.thevpc.naru.api.project.NaruProjectService service = projectServiceAt(dir);
                if (service == null) {
                    throw new IllegalStateException(
                            "no project service is installed for " + dir);
                }
                synchronized (projectListenersOwned) {
                    if (projectListenersOwned.add(dir.toString())) {
                        service.addListener(new net.thevpc.naru.api.project.NaruProjectListener() {
                            @Override public void onProjectCreated(net.thevpc.naru.api.project.NaruProject p) { onEvent(); }
                            @Override public void onProjectUpdated(net.thevpc.naru.api.project.NaruProject b, net.thevpc.naru.api.project.NaruProject a) { onEvent(); }
                            @Override public void onProjectRemoved(net.thevpc.naru.api.project.NaruProject p) { onEvent(); }
                            @Override public void onSessionMoved(String sessionId, String from, String to) { onEvent(); }
                            private void onEvent() {
                                SwingUtilities.invokeLater(MainWindow.this::refreshProjectSnapshot);
                            }
                        });
                    }
                }
                java.util.List<net.thevpc.naru.desktop.model.SessionGrouping.Project> projects = new ArrayList<>();
                String cursor = null;
                do {
                    var page = service.tree(new net.thevpc.naru.api.project.NaruProjectQuery(
                            cursor, null, null, null, 200));
                    for (var node : page.items()) {
                        net.thevpc.naru.api.project.NaruProject p = node.project();
                        projects.add(new net.thevpc.naru.desktop.model.SessionGrouping.Project(
                                p.id(), p.name(), p.folder(), p.icon(), p.color(), p.description()));
                    }
                    cursor = page.nextCursor();
                } while (cursor != null);
                java.util.Map<String, String> projectOf = new java.util.HashMap<>();
                cursor = null;
                do {
                    var page = service.sessions(new net.thevpc.naru.api.store.NaruSessionQuery(
                            cursor, null, null, null, 500));
                    for (var s : page.items()) {
                        if (s.projectId() != null) projectOf.put(s.uuid(), s.projectId());
                    }
                    cursor = page.nextCursor();
                } while (cursor != null);
                return new ProjectSnapshot(projects, projectOf);
            }

            @Override
            protected void done() {
                try {
                    projectSnapshot = get();
                    projectReadError = null;
                } catch (Exception e) {
                    // Keep the last good tree rather than dropping to the flat listing: the
                    // comment below used to promise exactly this, and the assignment to null
                    // ungrouped the sidebar for one store hiccup. Only a workspace that never
                    // answered once falls back to flat.
                    if (projectSnapshot == null) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        projectReadError = cause.getMessage() == null
                                ? cause.getClass().getSimpleName() : cause.getMessage();
                    }
                }
                rebuildSessionList();
            }
        }.execute();
    }

    private void activateSession(SessionEntry selected) {
        if (selected == null || selected.isGroup()) return;
        disposeSessionViews();
        if (selected.model == null) {
            InteractionHost selectedHost = new InteractionHost();
            selectedHost.startFlushTimer();
            selected.host = selectedHost;
            NaruSessionModel previous = selected.pendingTeardown;
            selected.pendingTeardown = null;
            selected.model = new NaruSessionModel(() -> {
                // The supplier runs on the boot worker: wait out the previous activation's
                // teardown before the workspace exists, so its background save of this same
                // session id cannot race this boot's reads and writes. A teardown that somehow
                // outlives the budget must not wedge the open forever, hence the bound.
                if (previous != null) previous.awaitShutdown(REOPEN_TEARDOWN_WAIT_MS);
                return NaruDesktop.workspace(selected.projectDir);
            }, selected.projectDir, selectedHost, selected.id, selected.scope);
            selected.model.start();
        }
        session = selected.model;
        host = selected.host;
        loadingListener = () -> {
            if (selected.model.isBooting()) showLoading("Opening " + selected.toString() + "…");
            else { hideLoading(); if (selected.model == session) showActiveSession(selected.toString(), selected); }
            if (selected.model.session() != null) {
                selected.id = selected.model.session().uuid();
            }
            syncPlanTab();
            sessionList.repaint();
        };
        selected.model.addListener(loadingListener);
        if (selected.model.isBooting()) showLoading("Opening " + selected.toString() + "…");
        sessionListSync = sessionList::repaint;
        selected.model.addListener(sessionListSync);
        transcript = new TranscriptPane(session);
        transcript.setTaskFilter(Long.MIN_VALUE);
        transcript.setMessageActionHandler(session::submitMessageAction);
        sessionLogPane = new TranscriptPane(session, false);
        sessionLogPane.setTaskFilter(Long.MIN_VALUE);
        transcript.setJumpToPermissions(ruleId -> openPermissions(ruleId));
        input = new InputPane(session, this::showToolTags);
        statusBar = new StatusBar(session);
        TaskPane tasks = new TaskPane(session);
        taskPane = tasks;
        JTabbedPane conversationViews = new JTabbedPane();
        conversationTabs = conversationViews;
        conversationViews.addTab("Conversation", transcript);
        conversationViews.addTab("Logs", sessionLogPane);
        JTabbedPane taskTools = sideTabs(tasks, session);
        tasks.setTaskSelectionListener(id -> {
            transcript.setTaskFilter(id);
            input.setSelectedTaskId(id);
            taskScopeHeader.setText(id < 0 ? "No task selected" : "Task #" + id);
            if (workbench != null) workbench.setSelectedTaskId(id);
            // the inspector carries the tools table too, so one call scopes both to this task
            if (inspector != null) inspector.setSelectedTaskId(id);
            conversationViews.setSelectedIndex(0);
        });
        selectionSync = () -> {
            net.thevpc.naru.api.agent.NaruInputRequest pending = session.pendingRequest();
            if (pending != null) tasks.selectTask(pending.task().id());
        };
        session.addListener(selectionSync);
        JSplitPane sessionAndConversation = SplitPanes.horizontal(sessionRail(), conversationViews);
        sessionAndConversation.setResizeWeight(0.33);
        JSplitPane whole = SplitPanes.horizontal(sessionAndConversation, taskTools);
        whole.setResizeWeight(0.78);
        // The transcript and the composer share the middle column through a draggable divider, so
        // the user can give the composer room for a long prompt (or hide it) instead of living
        // with whatever fixed height the layout picked. The divider is remembered for the next
        // session/relaunch; resizeWeight keeps the transcript growing when the window does.
        JSplitPane conversationAndComposer = SplitPanes.vertical(whole, input);
        conversationAndComposer.setResizeWeight(1.0);
        // Zero is not a divider location, it is the absence of one. Applying it collapsed the
        // whole conversation column to nothing on the first session of a run, which read as
        // "the window only shows the input area". A remembered value is applied only when there
        // is one; otherwise the split is placed once it has a height, so the first activation
        // shows the conversation above a composer with room to write.
        if (composerDivider > 0) {
            conversationAndComposer.setDividerLocation(composerDivider);
        } else {
            conversationAndComposer.addComponentListener(new java.awt.event.ComponentAdapter() {
                private boolean placed;

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (placed || composerDivider > 0) {
                        return;
                    }
                    int total = conversationAndComposer.getHeight();
                    int composerHeight = Math.max(input.getPreferredSize().height, total / 3);
                    if (total > 0 && composerHeight > 0) {
                        placed = true;
                        conversationAndComposer.setDividerLocation(Math.max(0, total - composerHeight));
                    }
                }
            });
        }
        conversationAndComposer.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            Object value = e.getNewValue();
            if (value instanceof Integer location && location > 0) composerDivider = location;
        });
        composerSplit = conversationAndComposer;
        JPanel body = new JPanel(new BorderLayout());
        body.add(conversationAndComposer, BorderLayout.CENTER);
        // the footer, not a header: the reader's eye is on the composer above this row, and the
        // session, the model and the turn state belong where that eye already lands
        body.add(statusBar, BorderLayout.SOUTH);
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(body, BorderLayout.CENTER);
        center.revalidate(); center.repaint();
        showActiveSession(selected.toString(), selected);
        tasks.sessionModelChanged();
        sessionList.repaint();
        if (selected.model.isBooting()) showLoading("Opening " + selected.toString() + "…"); else hideLoading();
    }

    private void showSettings() {
        SettingsPane pane = new SettingsPane(NaruDesktop.selectedTheme(), theme -> {
            try { NaruDesktop.applyTheme(theme); }
            catch (RuntimeException ex) { net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this,"Appearance",String.valueOf(ex.getMessage())); }
        }, NaruDesktop.fontScale(), NaruDesktop::setFontScale, this::showWorkspace,
            // the desktop's singleton workspace is built during startup catalog loading; asking
            // for it here never bootstraps a second one. The live session adds its registry keys
            // to the completion catalog, and is null while no session is open.
            () -> NaruDesktop.workspace(projectDirectory),
            () -> session == null ? null : session.session());
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(pane, BorderLayout.CENTER);
        center.revalidate(); center.repaint();
    }

    private void showWorkspace() {
        if (session != null) {
            SessionEntry active = sessions.stream().filter(e -> e.model == session).findFirst().orElse(null);
            if (active != null) activateSession(active);
        } else addWelcome();
    }

    private JPanel sessionRail() {
        JPanel rail = new JPanel(new BorderLayout());
        sessionRailPanel = rail;
        rail.setPreferredSize(new java.awt.Dimension(railWidth(), 100));
        Color borderColor = javax.swing.UIManager.getColor("Component.borderColor");
        rail.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, borderColor != null ? borderColor : new Color(210, 215, 225)));
        JPanel sessionHeader = new JPanel(new BorderLayout());
        JLabel sessionTitle = new JLabel("Sessions");
        net.thevpc.naru.desktop.ui.UiFonts.apply(sessionTitle, net.thevpc.naru.desktop.ui.UiFonts.HEADING,
                java.awt.Font.BOLD);
        sessionTitle.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 0));
        sessionHeader.add(sessionTitle, BorderLayout.WEST);
        JPanel sessionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        // Two glyph buttons of one kind. They used to be a flat tool-bar arrow beside a rounded
        // pill, which read as two different classes of control for two actions of the same class;
        // "▶" is also the glyph the closed-session preview already uses for opening.
        JButton open = new JButton("▶");
        open.setFocusable(false);
        open.setToolTipText("Open the selected session");
        open.getAccessibleContext().setAccessibleName("Open selected session");
        open.putClientProperty("JButton.buttonType", "toolBarButton");
        net.thevpc.naru.desktop.ui.ActionButtons.glyphSized(open);
        open.addActionListener(e -> { SessionEntry selected = sessionList.getSelectedValue(); if (selected != null) activateSession(selected); });
        // No new-session button here: a session is created in a group, so its door is the plus
        // painted on that group's row. The rail keeps the two actions that are about the picked
        // row rather than about a group.
        // Group management lives beside the session buttons, not only in the right-click
        // menu: a fresh workspace has a header to right-click only once it is grouped, and
        // one button carries new/rename/delete instead of three glyphs crowding the header.
        JButton groups = new JButton("⊞");
        groups.setFocusable(false);
        groups.setToolTipText("Groups — new, rename, settings, delete");
        groups.getAccessibleContext().setAccessibleName("Groups");
        groups.putClientProperty("JButton.buttonType", "toolBarButton");
        net.thevpc.naru.desktop.ui.ActionButtons.glyphSized(groups);
        groups.addActionListener(e -> showGroupMenuAt(groups));
        sessionActions.add(open);
        sessionActions.add(groups);
        sessionHeader.add(sessionActions, BorderLayout.EAST);
        if (!sessionFilterBound) {
            sessionFilterBound = true;
            sessionFilter.putClientProperty("JTextField.placeholderText", "Filter sessions…");
            sessionFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildSessionList(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildSessionList(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildSessionList(); }
            });
        }
        sessionList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        applySessionRowHeight();
        installSessionHover();
        // Drag a session row onto a group header to move it; dropping onto another
        // session means "the group that row sits in". The drop runs the same
        // projectId-only move as the context menu — never a directory change, never a
        // storage move — so a running session can be re-parented mid-flight.
        sessionList.setDragEnabled(true);
        sessionList.setDropMode(javax.swing.DropMode.ON);
        sessionList.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            protected java.awt.datatransfer.Transferable createTransferable(javax.swing.JComponent c) {
                SessionEntry dragged = sessionList.getSelectedValue();
                if (dragged == null || dragged.isGroup()) return null;
                return new java.awt.datatransfer.StringSelection(dragged.id);
            }

            @Override
            public int getSourceActions(javax.swing.JComponent c) {
                return javax.swing.TransferHandler.MOVE;
            }

            @Override
            public boolean canImport(javax.swing.TransferHandler.TransferSupport support) {
                return support.isDrop()
                        && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(javax.swing.TransferHandler.TransferSupport support) {
                if (!canImport(support)) return false;
                String sessionId;
                try {
                    sessionId = (String) support.getTransferable().getTransferData(
                            java.awt.datatransfer.DataFlavor.stringFlavor);
                } catch (Exception e) {
                    return false;
                }
                if (sessionId == null || sessionId.isBlank()) return false;
                int row = ((javax.swing.JList.DropLocation) support.getDropLocation()).getIndex();
                net.thevpc.naru.desktop.model.SessionGrouping.Header target = headerAtOrAbove(row);
                // the unknown-project bucket is a view of dangling ids, not a place a
                // session can be moved to; and a row dropped on itself is a no-op
                if (target == null || target.unknown()) return false;
                SessionEntry droppedOn = row >= 0 && row < sessionList.getModel().getSize()
                        ? sessionList.getModel().getElementAt(row) : null;
                if (droppedOn != null && !droppedOn.isGroup() && sessionId.equals(droppedOn.id)) return false;
                moveSessionTo(sessionId, target.projectId(), target.title());
                return true;
            }
        });
        sessionList.setCellRenderer((list, value, index, selected, focus) -> {
            if (value.isGroup()) {
                // a section label, not a session: no dot, no state, no dates, and it
                // never borrows the selection hue — painting a picked project looked like
                // a picked session
                JLabel head = new JLabel("<html><div style='padding:3px 4px 1px 6px;'>"
                        + "<span style='color:" + UiPalette.css(UiPalette.muted()) + ";'>"
                        + groupTitleHtml(value) + "</span></div></html>");
                head.setOpaque(false);
                JPanel row = new JPanel(new BorderLayout());
                row.setOpaque(true);
                row.setBackground(list.getBackground());
                row.add(head, BorderLayout.CENTER);
                if (!value.header.unknown()) {
                    // The group's two doors, each in a band the hit-test reads the same way:
                    // which project a session is born into is decided by the row the plus was
                    // clicked on, not guessed afterwards, and the gear opens that group's own
                    // icon, colour and description.
                    boolean hovered = Integer.valueOf(index)
                            .equals(list.getClientProperty("naru.hoverRow"));
                    java.awt.Color actionColor = hovered
                            ? UiPalette.readable(UiPalette.accent()) : UiPalette.muted();
                    JPanel actions = new JPanel(new java.awt.GridLayout(1, 2));
                    actions.setOpaque(false);
                    JLabel gear = new JLabel("⚙", javax.swing.SwingConstants.CENTER);
                    JLabel plus = new JLabel("＋", javax.swing.SwingConstants.CENTER);
                    for (JLabel chip : new JLabel[]{gear, plus}) {
                        chip.setOpaque(false);
                        chip.setForeground(actionColor);
                        chip.setPreferredSize(new java.awt.Dimension(GROUP_ACTION_BAND, 1));
                    }
                    actions.add(gear);
                    actions.add(plus);
                    row.add(actions, BorderLayout.EAST);
                }
                return row;
            }
            String dates = value.dateSummary();
            String state = value.stateLabel();
            // one dot in the state's hue, the same hue the title row uses for the session being read:
            // the mark glyph used to change shape per state, which was a second vocabulary for the
            // thing the word beside it already spelled out
            String mark = "●";
            // The date and the state pill are painted with fixed colours, which a selected row
            // replaces with the LAF's selection background. On a light LAF that selection is a
            // saturated blue, and a grey "Created …" on it disappears; selected rows therefore
            // take the selection foreground for both the name and the metadata.
            String metaColor = selected ? UiPalette.css(list.getSelectionForeground()) : UiPalette.css(UiPalette.muted());
            String stateColor = selected ? UiPalette.css(list.getSelectionForeground()) : UiPalette.css(value.stateColor());
            // The dot answers "is this session live?", the selection answers "is this the row I
            // picked?", and one row can be either, both, or neither. The selection hue therefore
            // never paints over the state hue — a closed session the reader has clicked on keeps its
            // grey dot instead of borrowing the green of the one that is actually running.
            String dotColor = UiPalette.css(value.stateColor());
            // Bold is spent on the single thing the list cannot show another way: the session this
            // window is reading. Selected-but-closed and open-but-unselected both used to be bold,
            // which made every row bold and so none of them one.
            boolean active = value.model != null && value.model == session;
            String name = active ? "<b>" + value + "</b>" : value.toString();
            float meta = net.thevpc.naru.desktop.ui.UiFonts.pointSize(net.thevpc.naru.desktop.ui.UiFonts.CAPTION);
            JLabel label = new JLabel("<html><div style='padding:2px 4px;'>"
                    + "<span style='color:" + dotColor + ";'>" + mark + "</span>  " + name
                    + "<br><span style='font-size:" + meta + "pt;color:" + stateColor + "'>" + state
                    + "</span><span style='font-size:" + meta + "pt;color:" + metaColor + ";'> · " + dates + "</span></div></html>");
            label.setOpaque(true);
            // A row the pointer rests on warms towards its own state hue. The selection already
            // says "this one is picked"; hover only says "this one is next", so it borrows the
            // colour the row carries instead of inventing a second highlight.
            Color surface = list.getBackground();
            if (!selected && Integer.valueOf(index).equals(list.getClientProperty("naru.hoverRow"))) {
                surface = UiPalette.mix(surface, value.stateColor(),
                        0.18f * net.thevpc.naru.desktop.ui.HoverFader.level(list));
            }
            label.setBackground(selected ? list.getSelectionBackground() : surface);
            label.setForeground(selected ? list.getSelectionForeground() : javax.swing.UIManager.getColor("List.foreground"));
            label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return label;
        });
        sessionList.setOpaque(true);
        sessionFilter.setOpaque(true);
        JPanel top=new JPanel(new BorderLayout(0,5)); JPanel heading=new JPanel(new BorderLayout(0,5)); heading.add(sessionHeader,BorderLayout.NORTH); heading.add(sessionFilter,BorderLayout.SOUTH);
        top.add(heading,BorderLayout.NORTH); top.add(new JScrollPane(sessionList),BorderLayout.CENTER);
        rail.add(top, BorderLayout.CENTER);
        return rail;
    }

    /**
     * The fade under the pointer in the session list. The level lives on the list, not on a row,
     * because a {@code JList} owns one component for many cells: the renderer asks the list which
     * row the pointer is on and how far in the fade has come, and paints that one.
     */
    private void installSessionHover() {
        net.thevpc.naru.desktop.ui.HoverFader.install(sessionList);
        sessionList.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setSessionHover(sessionList.locationToIndex(e.getPoint()));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                setSessionHover(-1);
            }
        });
        sessionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                setSessionHover(-1);
            }
        });
    }

    private void setSessionHover(int index) {
        int size = sessionList.getModel().getSize();
        int row = index >= 0 && index < size ? index : -1;
        if (Integer.valueOf(row).equals(sessionList.getClientProperty("naru.hoverRow"))) {
            return;
        }
        sessionList.putClientProperty("naru.hoverRow", row);
        sessionList.repaint();
    }

    private void showHibernatedSession(SessionEntry entry) {
        disposeSessionViews();
        // The engine may still be open in the background. This preview is only the active
        // workspace binding, so clear the UI's active reference; selecting that open row again
        // must rebuild its components on a single click.
        session = null;
        host = null;
        JPanel preview = new JPanel(new BorderLayout(12, 12));
        preview.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
        JLabel title = new JLabel("Session is closed");
        net.thevpc.naru.desktop.ui.UiFonts.apply(title,
                net.thevpc.naru.desktop.ui.UiFonts.SUBTITLE, java.awt.Font.BOLD);
        JLabel details = new JLabel("<html><b>" + entry.toString() + "</b><br>" + entry.dateSummary()
                + "<br><br>This session is closed. Open it to load its conversation, tasks, and workspace.</html>");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        // glyph and tooltip, like every other action button: the words are the first thing this row
        // loses when the interface size grows, and the card already says what the session is
        JButton open = new JButton("▶");
        open.setToolTipText("Open this session");
        open.getAccessibleContext().setAccessibleName("Open session");
        open.addActionListener(e -> activateSession(entry));
        JButton delete = new JButton("🗑");
        delete.setToolTipText("Delete this session…");
        delete.getAccessibleContext().setAccessibleName("Delete session");
        delete.addActionListener(e -> deleteSession(entry));
        actions.add(open); actions.add(delete);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.add(title, BorderLayout.NORTH);
        content.add(details, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        preview.add(content, BorderLayout.NORTH);
        JSplitPane split = SplitPanes.horizontal(sessionRail(), preview);
        split.setResizeWeight(0.24);
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(split, BorderLayout.CENTER);
        showActiveSession(entry.toString(), entry);
        center.revalidate(); center.repaint();
        hideLoading();
    }

    private static final class SessionEntry {
        String id;
        final net.thevpc.naru.api.store.NaruSessionScope scope;
        final NPath projectDir;
        final net.thevpc.naru.api.agent.NaruResourceInfo savedInfo;
        NaruSessionModel model;
        InteractionHost host;
        /**
         * The model this row last closed with, kept only while its engine teardown runs in the
         * background. The next activation waits that out before touching the store, so the save
         * finishing behind us cannot interleave with the save a reopen performs on the same
         * session record; once consumed the reference is dropped with the row.
         */
        NaruSessionModel pendingTeardown;

        /**
         * The group this row was created under, kept because a brand-new session has no uuid
         * yet and the store listing cannot say where it belongs. The snapshot map still wins
         * for any row it knows, so a move performed elsewhere (or a detach, which removes the
         * row from the map and from this field in the same action) is never overridden here.
         */
        String projectId;

        SessionEntry(String id, net.thevpc.naru.api.store.NaruSessionScope scope,
                     NPath projectDir, net.thevpc.naru.api.agent.NaruResourceInfo savedInfo,
                     NaruSessionModel model, InteractionHost host) {
            this.id = id; this.scope = scope; this.projectDir = projectDir;
            this.savedInfo = savedInfo; this.model = model; this.host = host; this.header = null;
        }

        /** A group row: it names a project (or the standalone/unknown buckets) and holds no session. */
        private SessionEntry(net.thevpc.naru.desktop.model.SessionGrouping.Header header) {
            this.id = null; this.scope = null; this.projectDir = null;
            this.savedInfo = null; this.model = null; this.host = null; this.header = header;
        }

        final net.thevpc.naru.desktop.model.SessionGrouping.Header header;

        static SessionEntry group(net.thevpc.naru.desktop.model.SessionGrouping.Header header) {
            return new SessionEntry(header);
        }

        /** True for the project/no-project/unknown rows interleaved with the sessions. */
        boolean isGroup() { return header != null; }

        boolean isOpen() { return model != null && model.isRunning(); }
        boolean isOpening() { return model != null && model.isBooting(); }
        boolean needsAttention() { return model != null && model.statusText() != null
                && model.statusText().toLowerCase(java.util.Locale.ROOT).contains("fail"); }
        String stateLabel() {
            if (isOpening()) return "Opening";
            if (isOpen()) return "Open";
            if (needsAttention()) return "Needs attention";
            return "Closed";
        }

        /** The hue of that label, shared by the session row and the dot in the title row. */
        Color stateColor() {
            if (isOpening()) return UiPalette.PENDING;
            if (isOpen()) return UiPalette.LIVE;
            if (needsAttention()) return UiPalette.ATTENTION;
            return UiPalette.IDLE;
        }

        @Override public String toString(){
            if (isGroup()) {
                int n = header.count();
                return header.title() + (header.projectId() == null && !header.unknown() && n == 0 ? "" : " (" + n + ")");
            }
            // The row's label is the session's stored name, always. There is no computed
            // "Session N" left to fall back to: a fresh session is minted a placeholder by the
            // store's counter, and the only rows with no name are ones whose engine has not
            // answered yet.
            if (model != null && model.session() != null) return model.session().name();
            if (savedInfo != null) {
                String saved = savedInfo.getName();
                if (saved != null && !saved.isBlank()) return saved;
            }
            return "Opening…";
        }
        String dateSummary() {
            if (model != null && model.session() != null) {
                var s = model.session();
                return "Created " + shortDate(s.creationInstant()) + " · Updated " + shortDate(s.modificationInstant());
            }
            if (savedInfo != null) return "Created " + shortDate(savedInfo.getCreationInstant())
                    + " · Updated " + shortDate(savedInfo.getModificationInstant());
            return "Opening…";
        }
        private static String shortDate(java.time.Instant value) {
            if (value == null) return "—";
            return java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault()).format(value);
        }
    }

    private JTabbedPane sideTabs;
    private JTabbedPane conversationTabs;
    /** The inspector's own strip: Tools, Requests, Context, Budget and Skills are its children. */
    private JTabbedPane inspectorTabs;

    private JTabbedPane sideTabs(TaskPane tasks, NaruSessionModel session) {
        JTabbedPane tabs = new JTabbedPane();tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // The plan board leads the rail when the plan extension is installed: it says what the run
        // is for, where the tabs after it answer questions about a run that already happened. It is
        // added by syncPlanTab() once the session has booted, because the extension may not be in
        // the profile; a session without planning should not show an empty Plan tab.
        // moved from Phase 4 by the owner: §3.3 makes this the v1 answer to an approval prompt,
        // and §6 promises a jump into it from a permission denial
        JPanel taskPermissions = new JPanel(new BorderLayout());
        taskPermissions.add(taskScopeHeader, BorderLayout.NORTH);
        JLabel pendingPermissions = new JLabel("Per-task permissions and approvals will appear here.", JLabel.CENTER);
        pendingPermissions.setForeground(java.awt.Color.GRAY);
        taskPermissions.add(pendingPermissions, BorderLayout.CENTER);
        tabs.addTab("Permissions", taskPermissions);
        // the tools table lives inside Inspect now: it is an inspection surface, and beside the
        // requests table its columns and its detail zone read as one instrument instead of two
        inspector = new InspectorPane(session, tasks);
        inspector.setJumpToPermissions(this::openPermissions);
        inspectorTabs = inspector.tabStrip();
        tabs.addTab("Inspect", inspector);
        workbench = new WorkbenchPane(session);
        tabs.addTab("Workspace", workbench);
        // The first-level tabs get a shape each. The words stay — they are what selectSideTab and
        // a screenshot of "the Permissions tab" address — but grey words in a two-row strip are
        // hard to tell apart at a glance, and the tooltip says what is behind a tab before it is opened.
        TabGlyphs.decorate(tabs, tabs.indexOfTab("Permissions"), TabGlyphs.Kind.PERMISSIONS,
                "what this task may and may not do");
        TabGlyphs.decorate(tabs, tabs.indexOfTab("Inspect"), TabGlyphs.Kind.INSPECT,
                "the tools called and the model requests made");
        TabGlyphs.decorate(tabs, tabs.indexOfTab("Workspace"), TabGlyphs.Kind.WORKSPACE,
                "the files, the changes and the tags");
        // One pixel of underline vanishes in a strip that wraps to two rows.
        tabs.putClientProperty("JTabbedPane.tabSelectionHeight", 3);
        this.sideTabs = tabs;
        syncPlanTab();
        return tabs;
    }

    /**
     * Add or remove the Plan tab to match what this session actually loaded.
     *
     * <p>The extension is resolved by the session, not compiled in, so whether there is a plan
     * service is only known after boot. The model listener calls this on every change; a session
     * whose profile has no plan extension therefore never grows a tab that could only say "not
     * installed".
     */
    private void syncPlanTab() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::syncPlanTab);
            return;
        }
        JTabbedPane tabs = sideTabs;
        NaruSessionModel current = session;
        if (tabs == null || current == null) {
            return;
        }
        boolean available;
        try {
            available = NaruPlanService.of(current.session()) != null;
        } catch (RuntimeException e) {
            available = false;
        }
        int index = tabs.indexOfTab("Plan");
        if (available && index < 0) {
            planPane = new PlanPane(current);
            tabs.insertTab("Plan", null, planPane, null, 0);
            TabGlyphs.decorate(tabs, 0, TabGlyphs.Kind.PLAN,
                    "what the run is for, step by step");
        } else if (!available && index >= 0) {
            tabs.remove(index);
            if (planPane != null) {
                planPane.dispose();
                planPane = null;
            }
        }
    }

    /**
     * Jump from a denial to the rule that caused it (plan §6). A null id means the default effect
     * denied the call, which is still worth showing — the fallback combo is what to change — but no
     * row can be selected for it.
     */
    public void openPermissions(String ruleId) {
        // the tab is titled "Permissions"; asking for "Task permissions" matched nothing, so the
        // denial jump the pane advertises switched to no tab at all
        if (!selectSideTab("Permissions")) {
            return;
        }
    }

    /** Jump from the composer's tag strip to the full catalog: the Workspace tab's Tool tags pane. */
    public void showToolTags() {
        if (selectSideTab("Workspace") && workbench != null) {
            workbench.showToolTagsTab();
        }
    }

    /**
     * Show one of the side tabs by title. The gate harness needs it: a screenshot of the transcript
     * cannot be evidence that the Tools tab works, and this is the only way to look at it from
     * outside the class.
     */
    public boolean selectSideTab(String title) {
        JTabbedPane tabs = tabsHolding(title);
        if (tabs == null && sideTabs != null && sideTabs.indexOfTab("Inspect") >= 0) {
            // the inspector's own strip is only populated once Inspect has been shown, so a harness
            // that asks for a child first has to raise the parent before the child can be found
            sideTabs.setSelectedIndex(sideTabs.indexOfTab("Inspect"));
            tabs = tabsHolding(title);
        }
        if (tabs == null) {
            return false;
        }
        // an inspector child is only on screen once Inspect itself is showing: a harness that asks
        // for "Tools · task" gets that child photographed, not the parent's blank remainder
        if (tabs == inspectorTabs && sideTabs != null) {
            sideTabs.setSelectedIndex(sideTabs.indexOfTab("Inspect"));
        }
        tabs.setSelectedIndex(tabs.indexOfTab(title));
        lastTabbedPane = tabs;
        return true;
    }

    /**
     * Which tabbed pane owns a title. The window has three — the rail's tool tabs, the conversation
     * tabs beside the transcript, and the inspector's own strip — and a caller that asks for
     * {@code "Conversation"} must be told the truth about it, not about whichever side tab happened
     * to be selected. Before this, selecting a conversation tab worked while
     * {@code selectedSideComponent()} still returned the Workspace pane, so a gate run's screenshot
     * of "Conversation" was a screenshot of the wrong thing.
     */
    private JTabbedPane tabsHolding(String title) {
        JTabbedPane tabs = sideTabs;
        if (tabs != null && tabs.indexOfTab(title) >= 0) {
            return tabs;
        }
        tabs = conversationTabs;
        if (tabs != null && tabs.indexOfTab(title) >= 0) {
            return tabs;
        }
        tabs = inspectorTabs;
        return tabs != null && tabs.indexOfTab(title) >= 0 ? tabs : null;
    }

    /** The tabbed pane the last successful selection went through, so the getters agree with it. */
    private JTabbedPane lastTabbedPane;

    private JTabbedPane tabsHoldingAny() {
        return sideTabs != null ? sideTabs : conversationTabs;
    }

    /** The tab now showing, in whichever tabbed pane the harness is driving. */
    public String selectedSideTab() {
        JTabbedPane tabs = lastTabbedPane != null ? lastTabbedPane : tabsHoldingAny();
        return tabs == null || tabs.getSelectedIndex() < 0 ? null : tabs.getTitleAt(tabs.getSelectedIndex());
    }

    /**
     * What the selected tab shows, in whichever tabbed pane was last driven. A gate run photographs
     * the panel it just opened, and the side panel is the part of a 1400-pixel window a full-screen
     * capture tends to cut off.
     */
    public java.awt.Component selectedSideComponent() {
        JTabbedPane tabs = lastTabbedPane != null ? lastTabbedPane : tabsHoldingAny();
        return tabs == null ? null : tabs.getSelectedComponent();
    }

    /**
     * The rule the Permissions tab has selected, for a gate run that has to prove a jump landed on
     * the right row rather than merely switched tabs.
     */
    public String selectedPermissionRule() {
        return null;
    }

    /**
     * Close the active session while keeping the desktop's shared workspace alive for other tabs.
     */
    private void closeSession() {
        SessionEntry active = null;
        for (SessionEntry entry : sessions) if (entry.model == session) { active = entry; break; }
        if (active != null) hibernateSession(active);
    }

    private void hibernateSession(SessionEntry active) {
        if (active == null || active.model == null || active.model.isBooting()) return;
        boolean wasActive = active.model == session;
        disposeSessionViews();
        active.model.shutdown();
        // shutdown() returns before the engine teardown finishes; the row keeps the model so the
        // next activation and a delete can wait for that teardown rather than race its save
        active.pendingTeardown = active.model;
        active.model = null;
        active.host = null;
        if (wasActive) { session = null; host = null; }
        rebuildSessionList();
        if (!sessions.isEmpty()) showHibernatedSession(active);
        else { showActiveSession("No session", null); addWelcome(); }
    }

    private void deleteSession(SessionEntry entry) {
        if (entry == null || entry.isGroup()) return;
        if (entry.model != null && entry.model.isBooting()) {
            net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, "Delete session", "Wait for the session to finish opening before deleting it.");
            return;
        }
        String name = entry.toString();
        if (!net.thevpc.naru.desktop.ui.DesktopDialog.confirm(this, "Delete session",
                "Permanently delete “" + name + "” and its saved conversation and history?", "Delete")) return;
        net.thevpc.naru.api.agent.NaruSession stoppedEngine = entry.model == null ? null : entry.model.session();
        if (entry.model != null) hibernateSession(entry);
        // set by the hibernateSession above, or left there by an earlier close of this row: the
        // teardown behind it may still be saving this very session record
        NaruSessionModel draining = entry.pendingTeardown;
        if (entry.id == null) {
            net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(this, "Delete session", "Wait for the session to finish opening before deleting it.");
            return;
        }
        showLoading("Deleting " + name + "…");
        new javax.swing.SwingWorker<Boolean, Void>() {
            protected Boolean doInBackground() {
                // shutdown() tears the engine down on its own worker now: wait that out before
                // deleting, or a save still in flight would re-create the record under us
                if (draining != null) draining.awaitShutdown(CLOSE_GRACE_MS);
                if (stoppedEngine != null) stoppedEngine.waitFor();
                return catalog(entry.projectDir).delete(entry.id);
            }
            protected void done() {
                hideLoading();
                try {
                    if (!get()) throw new IllegalStateException("The session was not found in its saved catalog.");
                    sessions.remove(entry);
                    rebuildSessionList();
                    if (sessions.isEmpty()) {
                        showActiveSession("No session", null);
                        addWelcome();
                    } else {
                        SessionEntry selected = sessionList.getSelectedValue();
                        if (selected == null) {
                            sessionList.setSelectedIndex(0);
                            selected = sessionList.getSelectedValue();
                        }
                        if (selected != null && selected.model == null) showHibernatedSession(selected);
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this, "Delete session", String.valueOf(c.getMessage()));
                }
            }
        }.execute();
    }

    private void disposeSessionViews() {
        if (session != null && loadingListener != null) session.removeListener(loadingListener);
        if (session != null && sessionListSync != null) session.removeListener(sessionListSync);
        loadingListener = null; sessionListSync = null;
        if (session != null && selectionSync != null) session.removeListener(selectionSync);
        selectionSync = null;
        if (transcript != null) {
            transcript.dispose();
            transcript = null;
        }
        if (sessionLogPane != null) { sessionLogPane.dispose(); sessionLogPane = null; }
        // panes listen to models the session owns, so they unsubscribe before the shutdown
        // rather than leaving a listener on a model that is about to fire one last time
        if (taskPane != null) { taskPane.dispose(); taskPane = null; }
        if (inspector != null) { inspector.dispose(); inspector = null; }
        if (planPane != null) { planPane.dispose(); planPane = null; }
        workbench = null;
        if (input != null) { input.dispose(); input = null; }
        if (statusBar != null) { statusBar.dispose(); statusBar = null; }
        composerSplit = null;
        sideTabs = null;
        conversationTabs = null;
        inspectorTabs = null;
    }

    public NaruSessionModel session() {
        return session;
    }

    /** The host behind the current session, for the gate harness's measurements. */
    public InteractionHost host() {
        return host;
    }

}
