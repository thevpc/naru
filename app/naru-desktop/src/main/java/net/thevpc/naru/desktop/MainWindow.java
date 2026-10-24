package net.thevpc.naru.desktop;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.InputPane;
import net.thevpc.naru.desktop.ui.StatusBar;
import net.thevpc.naru.desktop.ui.StatusDot;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.naru.desktop.ui.ToolsPane;
import net.thevpc.naru.desktop.ui.TranscriptPane;
import net.thevpc.naru.desktop.ui.UiPalette;
import net.thevpc.naru.desktop.ui.WorkbenchPane;
import net.thevpc.naru.desktop.ui.SettingsPane;
import net.thevpc.naru.desktop.ui.SplitPanes;
import net.thevpc.naru.desktop.ui.InspectorPane;
import net.thevpc.naru.desktop.ui.TextAreas;
import net.thevpc.nuts.io.NPath;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
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
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
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
    private final JList<SessionEntry> sessionList = new JList<>(sessionRows);
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

    private TranscriptPane transcript;
    private TranscriptPane sessionLogPane;
    private InputPane input;
    private StatusBar statusBar;
    private ToolsPane tools;
    private TaskPane taskPane;
    private WorkbenchPane workbench;
    private InspectorPane inspector;
    private NaruSessionModel session;
    private InteractionHost host;
    private Point dragOffset;
    private boolean maximized;
    /** Where the window was before it went full screen, for the restore half of {@link #toggleMaximize()}. */
    private java.awt.Rectangle restoredBounds;
    /** The frame cursor already asked for, so hovering an edge does not re-send the same request. */
    private Cursor appliedCursor;
    private int nextSessionNumber = 1;
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
        sessionList.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("F2"), "rename-session");
        sessionList.getActionMap().put("rename-session", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { renameSelectedSession(); }
        });
        sessionList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && sessionList.locationToIndex(e.getPoint()) >= 0) {
                    SessionEntry entry = sessionList.getSelectedValue();
                    if (entry != null) activateSession(entry);
                }
            }
            @Override public void mousePressed(MouseEvent e) { showMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { showMenu(e); }
            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = sessionList.locationToIndex(e.getPoint());
                if (row < 0) return;
                sessionList.setSelectedIndex(row);
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                SessionEntry entry = sessionList.getSelectedValue();
                javax.swing.JMenuItem openClose = new javax.swing.JMenuItem(entry.isOpen() ? "Close session" : "Open session");
                openClose.addActionListener(x -> {
                    if (entry.isOpen()) hibernateSession(entry);
                    else activateSession(entry);
                });
                menu.add(openClose);
                javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Rename session…");
                rename.addActionListener(x -> renameSelectedSession()); menu.add(rename);
                menu.addSeparator();
                javax.swing.JMenuItem delete = new javax.swing.JMenuItem("Delete session…");
                delete.addActionListener(x -> deleteSession(entry)); menu.add(delete);
                menu.show(sessionList, e.getX(), e.getY());
            }
        });
    }

    private JPanel topBar() {
        JPanel shell = new JPanel(new BorderLayout());
        JMenuBar menus = new JMenuBar();
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
        shell.add(menus, BorderLayout.WEST);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 5));
        bar.setOpaque(false);
        // Left empty: it is the drag handle for the window. The session it used to name is stated in
        // the status footer, where the reader looks after a turn ends, and in the taskbar title.

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        JButton settings = new JButton("⚙"); settings.setToolTipText("Settings"); settings.getAccessibleContext().setAccessibleName("Settings");
        settings.setFocusable(false); settings.putClientProperty("JButton.buttonType", "toolBarButton");
        settings.addActionListener(e -> showSettings()); controls.add(settings);

        JButton min = new JButton("—"); min.setToolTipText("Minimize"); min.addActionListener(e -> setState(ICONIFIED));
        JButton max = new JButton("□"); max.setToolTipText("Maximize / restore"); max.addActionListener(e -> toggleMaximize());
        JButton quit = new JButton("×"); quit.setToolTipText("Close"); quit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        for (JButton b : new JButton[]{min, max, quit}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            controls.add(b);
        }
        // the four controls carry one glyph each: a margin-sized box is wide enough at the font the
        // window was built with and elides the glyph to "…" once the interface size grows
        net.thevpc.naru.desktop.ui.ActionButtons.glyphSized(settings, min, max, quit);

        shell.add(bar, BorderLayout.CENTER); shell.add(controls, BorderLayout.EAST);
        java.awt.Color separator = javax.swing.UIManager.getColor("Component.borderColor");
        if (separator == null) separator = javax.swing.UIManager.getColor("Separator.foreground");
        shell.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, separator == null ? new Color(210, 215, 225) : separator));
        MouseAdapter drag = new MouseAdapter() {
            private MouseEvent handled;
            @Override public void mousePressed(MouseEvent e) { if (e.getButton()==MouseEvent.BUTTON1 && !maximized) { dragOffset=e.getLocationOnScreen(); Point p=getLocation(); dragOffset.translate(-p.x,-p.y); } }
            @Override public void mouseDragged(MouseEvent e) { if (dragOffset!=null && !maximized) { Point p=e.getLocationOnScreen(); setLocation(p.x-dragOffset.x,p.y-dragOffset.y); } }
            @Override public void mouseReleased(MouseEvent e) { dragOffset=null; }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) return;
                // This adapter sits on the bar and on every child of it that is not a control, and one
                // real double-click travels up from the child under the pointer: the same event object
                // is offered once to each of them. Answering twice would toggle twice and look dead.
                if (handled == e) return;
                handled = e;
                // Events from the control buttons arrive here by propagation, too; a double-click on
                // the maximize button has to stay the button's own single press.
                for (java.awt.Component c = e.getComponent(); c != null && c != shell; c = c.getParent())
                    if (c instanceof javax.swing.AbstractButton) return;
                toggleMaximize();
            }
        };
        installDrag(shell, drag);
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
        String value=net.thevpc.naru.desktop.ui.DesktopDialog.promptText(this,"Rename session","Session name",oldName==null||"NO_NAME".equals(oldName)?"":oldName);
        if(value==null||value.isBlank()) return;
        String newName=value.trim(); showLoading("Saving session name…");
            new javax.swing.SwingWorker<Void,Void>() {
                protected Void doInBackground(){entry.model.session().name(newName).save();return null;}
                protected void done(){hideLoading();try{get();rebuildSessionList();sessionList.repaint();showActiveSession(newName, entry);}catch(Exception ex){Throwable c=ex.getCause()==null?ex:ex.getCause();net.thevpc.naru.desktop.ui.DesktopDialog.showMessage(MainWindow.this,"Rename session",String.valueOf(c.getMessage()));}}
            }.execute();
    }

    private void installDrag(java.awt.Component c, MouseAdapter drag) {
        if (c instanceof JMenuBar || c instanceof JButton || c instanceof javax.swing.AbstractButton
                || c instanceof JList<?>) return;
        c.addMouseListener(drag); c.addMouseMotionListener(drag);
        if (c instanceof java.awt.Container container) for (java.awt.Component child : container.getComponents()) installDrag(child, drag);
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
        NPath dir = projectDirectory == null
                ? NPath.of(System.getProperty("user.dir", ".")).toAbsolute() : projectDirectory;
        openProject(dir);
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
                        sessions.add(new SessionEntry(nextSessionNumber++, info.getUuid(), scope,
                                projectDirectory, info, null, null));
                    }
                    rebuildSessionList();
                    if (sessions.isEmpty()) {
                        showActiveSession("No session", null);
                        addWelcome();
                    } else {
                        sessionList.setSelectedIndex(0);
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

    /**
     * Resize by dragging the window's own edges, since an undecorated window has no native frame to
     * grab. The listener sits on the frame, so it sees every mouse event the children propagate up
     * and must never claim one that belongs to a child: the edge is measured from the event's
     * component into this window's coordinates rather than trusting its raw point, and a press on a
     * divider is left to that divider.
     */
    private void installResizeSupport() {
        MouseAdapter resize = new MouseAdapter() {
            private int direction;
            private Point origin;
            private java.awt.Rectangle bounds;

            @Override public void mouseMoved(MouseEvent e) { hover(e); }

            @Override public void mouseExited(MouseEvent e) {
                // Unconditionally: leaving from an edge used to keep the resize shape, and the frame
                // cursor then painted over every component the pointer rested on.
                direction = 0;
                applyResizeCursor(0);
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
                // Taking the window out of the full-screen shape is a resize, not a maximize.
                if (maximized && !b.equals(screenWorkArea())) maximized = false;
            }

            @Override public void mouseReleased(MouseEvent e) {
                origin = null;
                hover(e);
            }

            private void hover(MouseEvent e) {
                Point where = inside(e);
                direction = where == null ? 0 : resizeDirection(where);
                applyResizeCursor(direction);
            }

            /**
             * Where the pointer is inside this window, or null when the window is no longer on screen.
             * <p>
             * The event's own point cannot be used: a mouse event travels up from the component under
             * the pointer, and AWT leaves its x/y in that component's coordinates while it travels. A
             * split divider is eight pixels wide, so read this way every press on a divider looks like
             * a press on the window's west edge — and the resize then steals the divider's own drag.
             * The event's absolute position is the one number that survives the trip.
             */
            private Point inside(MouseEvent e) {
                if (!isShowing()) return null;
                Point onScreen = e.getLocationOnScreen();
                Point here = getLocationOnScreen();
                return new Point(onScreen.x - here.x, onScreen.y - here.y);
            }

            /** True when the split pane under that point has its divider there, which needs the drag. */
            private boolean onDivider(Point where) {
                return findComponentAt(where.x, where.y) instanceof javax.swing.plaf.basic.BasicSplitPaneDivider;
            }
        };
        addMouseListener(resize);
        addMouseMotionListener(resize);
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
        center.add(p, BorderLayout.CENTER);
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
        SwingUtilities.invokeLater(() -> {
            host = new InteractionHost();
            // the queue drains from the first frame, so whatever the engine writes while the
            // workspace is still resolving shows up instead of being buffered behind the boot
            host.startFlushTimer();
            NaruSessionModel created = new NaruSessionModel(() -> NaruDesktop.workspace(dir), dir, host, restoreId, restoreScope);
            SessionEntry entry = new SessionEntry(nextSessionNumber++, restoreId, restoreScope, dir,
                    (net.thevpc.naru.api.agent.NaruResourceInfo) null, created, host);
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
                                        sessions.add(new SessionEntry(nextSessionNumber++, info.getUuid(),
                                                scope, dir, info, null, null));
                                    }
                                }
                                rebuildSessionList();
                                if (!saved.isEmpty()) SwingUtilities.invokeLater(() -> {
                                    if (sessionRows.size() > 0) sessionList.setSelectedIndex(0);
                                });
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

    private void rebuildSessionList() {
        SessionEntry selected = sessionList.getSelectedValue();
        DefaultListModel<SessionEntry> replacement = new DefaultListModel<>();
        String query = sessionFilter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        int selectedIndex = -1;
        for (SessionEntry entry : sessions) {
            if (query.isEmpty() || entry.toString().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                replacement.addElement(entry);
                if (entry == selected || entry.model == session) selectedIndex = replacement.size() - 1;
            }
        }
        // Populate off-list, then publish once. DefaultListModel emits one event per insertion;
        // BasicListUI recomputes renderer sizes on every event, making a large durable catalog an
        // O(n²) EDT task (the thread dump showed it stuck in updateLayoutState here).
        sessionRows = replacement;
        sessionList.setModel(replacement);
        if (selectedIndex >= 0) sessionList.setSelectedIndex(selectedIndex);
    }

    private void activateSession(SessionEntry selected) {
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
            tools.setTaskFilter(id);
            input.setSelectedTaskId(id);
            taskScopeHeader.setText(id < 0 ? "No task selected" : "Task #" + id);
            if (workbench != null) workbench.setSelectedTaskId(id);
            if (inspector != null) inspector.setSelectedTaskId(id);
            conversationViews.setSelectedIndex(0);
        });
        selectionSync = () -> {
            net.thevpc.naru.api.agent.NaruInputRequest pending = session.pendingRequest();
            if (pending != null) tasks.selectTask(pending.task().id());
        };
        session.addListener(selectionSync);
        JSplitPane sessionAndConversation = SplitPanes.horizontal(sessionRail(tasks), conversationViews);
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
        }, NaruDesktop.fontScale(), NaruDesktop::setFontScale, this::showWorkspace);
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(pane, BorderLayout.CENTER);
        center.revalidate(); center.repaint();
    }

    private void showWorkspace() {
        if (session != null) {
            SessionEntry active = sessions.stream().filter(e -> e.model == session).findFirst().orElse(null);
            if (active != null) activateSession(active);
        } else addWelcome();
    }

    private JPanel sessionRail(TaskPane tasks) {
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
        JButton add = new JButton("＋");
        add.setFocusable(false);
        add.setToolTipText("Create a session");
        add.getAccessibleContext().setAccessibleName("New session");
        add.putClientProperty("JButton.buttonType", "toolBarButton");
        net.thevpc.naru.desktop.ui.ActionButtons.glyphSized(add);
        add.addActionListener(e -> createSession());
        sessionActions.add(open);
        sessionActions.add(add);
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
        sessionList.setCellRenderer((list, value, index, selected, focus) -> {
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
        if (tasks == null) {
            rail.add(top, BorderLayout.CENTER);
        } else {
            // A ratio, not a pixel: a pixel location set here is clamped against the rail's
            // still-zero height and the divider lands on top of the list.
            JSplitPane left = SplitPanes.vertical(top, tasks);
            left.setResizeWeight(.25);
            left.setDividerLocation(0.45);
            rail.add(left, BorderLayout.CENTER);
        }
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
        JSplitPane split = SplitPanes.horizontal(sessionRail(null), preview);
        split.setResizeWeight(0.24);
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(split, BorderLayout.CENTER);
        showActiveSession(entry.toString(), entry);
        center.revalidate(); center.repaint();
        hideLoading();
    }

    private static final class SessionEntry {
        final int number;
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

        SessionEntry(int number, String id, net.thevpc.naru.api.store.NaruSessionScope scope,
                     NPath projectDir, net.thevpc.naru.api.agent.NaruResourceInfo savedInfo,
                     NaruSessionModel model, InteractionHost host) {
            this.number = number; this.id = id; this.scope = scope; this.projectDir = projectDir;
            this.savedInfo = savedInfo; this.model = model; this.host = host;
        }

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
            String name = model != null && model.session() != null ? model.session().name() : null;
            if (model == null || model.session() == null) name = savedInfo != null ? savedInfo.getName()
                    : null;
            return name == null || name.isBlank() || "NO_NAME".equals(name)
                    ? "Session " + number : name;
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

    private JTabbedPane sideTabs(TaskPane tasks, NaruSessionModel session) {
        JTabbedPane tabs = new JTabbedPane();
        tools = new ToolsPane(session.tools());
        tabs.addTab("Tools", tools);
        // moved from Phase 4 by the owner: §3.3 makes this the v1 answer to an approval prompt,
        // and §6 promises a jump into it from a permission denial
        JPanel taskPermissions = new JPanel(new BorderLayout());
        taskPermissions.add(taskScopeHeader, BorderLayout.NORTH);
        JLabel pendingPermissions = new JLabel("Per-task permissions and approvals will appear here.", JLabel.CENTER);
        pendingPermissions.setForeground(java.awt.Color.GRAY);
        taskPermissions.add(pendingPermissions, BorderLayout.CENTER);
        tabs.addTab("Permissions", taskPermissions);
        tools.setJumpToPermissions(ruleId -> openPermissions(ruleId));
        inspector = new InspectorPane(session);
        tabs.addTab("Inspect", inspector);
        workbench = new WorkbenchPane(session);
        tabs.addTab("Workspace", workbench);
        this.sideTabs = tabs;
        return tabs;
    }

    /**
     * Jump from a denial to the rule that caused it (plan §6). A null id means the default effect
     * denied the call, which is still worth showing — the fallback combo is what to change — but no
     * row can be selected for it.
     */
    public void openPermissions(String ruleId) {
        if (!selectSideTab("Task permissions")) {
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
        if (tabs == null) {
            return false;
        }
        tabs.setSelectedIndex(tabs.indexOfTab(title));
        lastTabbedPane = tabs;
        return true;
    }

    /**
     * Which tabbed pane owns a title. The window has two — the rail's tool tabs and the conversation
     * tabs beside the transcript — and a caller that asks for {@code "Conversation"} must be told the
     * truth about it, not about whichever side tab happened to be selected. Before this, selecting a
     * conversation tab worked while {@code selectedSideComponent()} still returned the Workspace pane,
     * so a gate run's screenshot of "Conversation" was a screenshot of the wrong thing.
     */
    private JTabbedPane tabsHolding(String title) {
        JTabbedPane tabs = sideTabs;
        if (tabs != null && tabs.indexOfTab(title) >= 0) {
            return tabs;
        }
        tabs = conversationTabs;
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
        if (entry == null) return;
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
        // both panes listen to models the session owns, so they unsubscribe before the shutdown
        // rather than leaving a listener on a model that is about to fire one last time
        if (tools != null) {
            tools.dispose();
            tools = null;
        }
        if (taskPane != null) { taskPane.dispose(); taskPane = null; }
        if (inspector != null) { inspector.dispose(); inspector = null; }
        workbench = null;
        if (input != null) { input.dispose(); input = null; }
        if (statusBar != null) { statusBar.dispose(); statusBar = null; }
        composerSplit = null;
        sideTabs = null;
        conversationTabs = null;
    }

    public NaruSessionModel session() {
        return session;
    }

    /** The host behind the current session, for the gate harness's measurements. */
    public InteractionHost host() {
        return host;
    }

}
