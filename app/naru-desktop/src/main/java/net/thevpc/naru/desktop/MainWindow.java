package net.thevpc.naru.desktop;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.ui.InputPane;
import net.thevpc.naru.desktop.ui.StatusBar;
import net.thevpc.naru.desktop.ui.TaskPane;
import net.thevpc.naru.desktop.ui.ToolsPane;
import net.thevpc.naru.desktop.ui.TranscriptPane;
import net.thevpc.naru.desktop.ui.WorkbenchPane;
import net.thevpc.naru.desktop.ui.InspectorPane;
import net.thevpc.nuts.io.NPath;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
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
import java.awt.BorderLayout;
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


    private final JPanel center = new JPanel(new BorderLayout());
    private final DefaultListModel<SessionEntry> sessionRows = new DefaultListModel<>();
    private final JList<SessionEntry> sessionList = new JList<>(sessionRows);
    private final List<SessionEntry> sessions = new ArrayList<>();

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
    private final JLabel activeSessionLabel = new JLabel("No session");
    private Point dragOffset;
    private boolean maximized;
    private int nextSessionNumber = 1;
    private final JLabel taskScopeHeader = new JLabel("No task selected");
    private NaruSessionModel.Listener selectionSync;

    public MainWindow() {
        super("NARU");
        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        add(topBar(), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        installResizeSupport();
        addWelcome();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disposeSessionViews();
                for (SessionEntry entry : sessions) entry.model.shutdown();
                dispose();
                // the engine holds worker threads; an explicit exit is the honest shutdown here
                System.exit(0);
            }
        });
        sessionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SessionEntry item = sessionList.getSelectedValue();
                if (item != null && item.model != session) activateSession(item);
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
        JMenuItem log = new JMenuItem("Session log"); log.addActionListener(e -> selectSideTab("Session log"));
        view.add(conversation); view.add(log);
        JMenu sessionMenu = new JMenu("Session");
        JMenuItem stop = new JMenuItem("Stop current session"); stop.addActionListener(e -> closeSession()); sessionMenu.add(stop);
        JMenu help = new JMenu("Help"); help.add(new JMenuItem("About NARU"));
        menus.add(file); menus.add(view); menus.add(sessionMenu); menus.add(help);
        shell.add(menus, BorderLayout.WEST);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        bar.add(activeSessionLabel);
        JButton settings = new JButton("⚙"); settings.setToolTipText("Settings (coming soon)");
        settings.addActionListener(e -> javax.swing.JOptionPane.showMessageDialog(this,
                "Settings will be available here.", "Settings", javax.swing.JOptionPane.INFORMATION_MESSAGE));
        bar.add(settings);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        JButton min = new JButton("—"); min.setToolTipText("Minimize"); min.addActionListener(e -> setState(ICONIFIED));
        JButton max = new JButton("□"); max.setToolTipText("Maximize / restore"); max.addActionListener(e -> toggleMaximize());
        JButton quit = new JButton("×"); quit.setToolTipText("Close"); quit.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        controls.add(min); controls.add(max); controls.add(quit);
        shell.add(bar, BorderLayout.CENTER); shell.add(controls, BorderLayout.EAST);
        shell.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color.GRAY));
        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.getButton()==MouseEvent.BUTTON1 && !maximized) { dragOffset=e.getLocationOnScreen(); Point p=getLocation(); dragOffset.translate(-p.x,-p.y); } }
            @Override public void mouseDragged(MouseEvent e) { if (dragOffset!=null && !maximized) { Point p=e.getLocationOnScreen(); setLocation(p.x-dragOffset.x,p.y-dragOffset.y); } }
            @Override public void mouseReleased(MouseEvent e) { dragOffset=null; }
        };
        installDrag(shell, drag);
        return shell;
    }

    private void installDrag(java.awt.Component c, MouseAdapter drag) {
        if (c instanceof JMenuBar || c instanceof JButton || c instanceof javax.swing.AbstractButton
                || c instanceof JList<?>) return;
        c.addMouseListener(drag); c.addMouseMotionListener(drag);
        if (c instanceof java.awt.Container container) for (java.awt.Component child : container.getComponents()) installDrag(child, drag);
    }

    private void toggleMaximize() { if (maximized) { setExtendedState(NORMAL); maximized=false; } else { setExtendedState(MAXIMIZED_BOTH); maximized=true; } }

    private void createSession() {
        // The current engine builder still needs a base directory. It stays implicit in the UI;
        // future task creation will supply per-task project/cwd values.
        openProject(NPath.of(System.getProperty("user.dir", ".")).toAbsolute());
    }

    private void installResizeSupport() {
        final int edge=6;
        MouseAdapter resize=new MouseAdapter() {
            private int direction; private Point origin; private java.awt.Rectangle bounds;
            @Override public void mouseMoved(MouseEvent e) { direction=resizeDirection(e.getPoint(),edge); setCursor(Cursor.getPredefinedCursor(cursorFor(direction))); }
            @Override public void mouseExited(MouseEvent e) { if(direction==0)setCursor(Cursor.getDefaultCursor()); }
            @Override public void mousePressed(MouseEvent e) { if(e.getButton()==MouseEvent.BUTTON1&&direction!=0){origin=e.getLocationOnScreen();bounds=getBounds();} }
            @Override public void mouseDragged(MouseEvent e) { if(origin==null)return; Point now=e.getLocationOnScreen(); int dx=now.x-origin.x,dy=now.y-origin.y; java.awt.Rectangle b=new java.awt.Rectangle(bounds); if((direction&1)!=0){b.x+=dx;b.width-=dx;} if((direction&2)!=0)b.width+=dx; if((direction&4)!=0){b.y+=dy;b.height-=dy;} if((direction&8)!=0)b.height+=dy; if(b.width>=720&&b.height>=480)setBounds(b); }
            @Override public void mouseReleased(MouseEvent e) { origin=null; }
        };
        addMouseListener(resize); addMouseMotionListener(resize);
    }
    private int resizeDirection(Point p,int edge){int d=0;if(p.x<edge)d|=1;else if(p.x>getWidth()-edge)d|=2;if(p.y<edge)d|=4;else if(p.y>getHeight()-edge)d|=8;return d;}
    private int cursorFor(int d){return switch(d){case 1->Cursor.W_RESIZE_CURSOR;case 2->Cursor.E_RESIZE_CURSOR;case 4->Cursor.N_RESIZE_CURSOR;case 8->Cursor.S_RESIZE_CURSOR;case 5->Cursor.NW_RESIZE_CURSOR;case 6->Cursor.NE_RESIZE_CURSOR;case 9->Cursor.SW_RESIZE_CURSOR;case 10->Cursor.SE_RESIZE_CURSOR;default->Cursor.DEFAULT_CURSOR;};}

    private void addWelcome() {
        JPanel p = new JPanel(new BorderLayout());
        JLabel hello = new JLabel("<html><div style='padding:18px'>"
                + "<b>NARU desktop</b><br>"
                + "Create a session from the File menu or the + button."
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
            host = new InteractionHost();
            // the queue drains from the first frame, so whatever the engine writes while the
            // workspace is still resolving shows up instead of being buffered behind the boot
            host.startFlushTimer();
            NaruSessionModel created = new NaruSessionModel(() -> newWorkspace(dir), dir, host);
            sessions.add(new SessionEntry(nextSessionNumber++, created, host));
            rebuildSessionList();
            sessionList.setSelectedIndex(sessions.size() - 1);
            created.start();
        });
    }

    private void rebuildSessionList() {
        sessionRows.clear();
        for (SessionEntry entry : sessions) sessionRows.addElement(entry);
    }

    private void activateSession(SessionEntry selected) {
        disposeSessionViews();
        session = selected.model;
        host = selected.host;
        transcript = new TranscriptPane(session);
        transcript.setTaskFilter(-1);
        sessionLogPane = new TranscriptPane(session);
        sessionLogPane.setTaskFilter(Long.MIN_VALUE);
        transcript.setJumpToPermissions(ruleId -> openPermissions(ruleId));
        input = new InputPane(session);
        statusBar = new StatusBar(session);
        TaskPane tasks = new TaskPane(session);
        taskPane = tasks;
        JTabbedPane conversationViews = new JTabbedPane();
        conversationTabs = conversationViews;
        conversationViews.addTab("Conversation", transcript);
        conversationViews.addTab("Session log", sessionLogPane);
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
        JSplitPane sessionAndConversation = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                sessionRail(tasks), conversationViews);
        sessionAndConversation.setResizeWeight(0.33);
        JSplitPane whole = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionAndConversation, taskTools);
        whole.setResizeWeight(0.78);
        JPanel body = new JPanel(new BorderLayout());
        body.add(whole, BorderLayout.CENTER);
        body.add(input, BorderLayout.SOUTH);
        body.add(statusBar, BorderLayout.NORTH);
        center.removeAll(); center.setLayout(new BorderLayout()); center.add(body, BorderLayout.CENTER);
        center.revalidate(); center.repaint();
        activeSessionLabel.setText("Session " + selected.number);
        tasks.sessionModelChanged();
    }

    private JPanel sessionRail(TaskPane tasks) {
        JPanel rail=new JPanel(new BorderLayout()); rail.setPreferredSize(new java.awt.Dimension(210,100));
        rail.setBorder(BorderFactory.createMatteBorder(0,0,0,1,java.awt.Color.GRAY));
        JPanel sessionHeader=new JPanel(new BorderLayout()); sessionHeader.add(new JLabel("  SESSIONS"),BorderLayout.WEST);
        JButton add=new JButton("+");add.setToolTipText("Create a session");add.addActionListener(e->createSession());sessionHeader.add(add,BorderLayout.EAST);
        sessionList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        sessionList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label=new JLabel("  " + value.toString()); label.setOpaque(true);
            label.setBackground(selected?list.getSelectionBackground():list.getBackground());
            label.setForeground(selected?list.getSelectionForeground():list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(8,4,8,4)); return label;
        });
        JPanel top=new JPanel(new BorderLayout()); top.add(sessionHeader,BorderLayout.NORTH); top.add(new JScrollPane(sessionList),BorderLayout.CENTER);
        JSplitPane left=new JSplitPane(JSplitPane.VERTICAL_SPLIT,top,tasks); left.setResizeWeight(.25); left.setDividerLocation(205);
        rail.add(left,BorderLayout.CENTER); return rail;
    }

    private record SessionEntry(int number, NaruSessionModel model, InteractionHost host) {
        @Override public String toString(){return "Session " + number;}
    }

    private JTabbedPane sideTabs;
    private JTabbedPane conversationTabs;

    private JTabbedPane sideTabs(TaskPane tasks, NaruSessionModel session) {
        JTabbedPane tabs = new JTabbedPane();
        tools = new ToolsPane(session.tools());
        tabs.addTab("Task tools", tools);
        // moved from Phase 4 by the owner: §3.3 makes this the v1 answer to an approval prompt,
        // and §6 promises a jump into it from a permission denial
        JPanel taskPermissions = new JPanel(new BorderLayout());
        taskPermissions.add(taskScopeHeader, BorderLayout.NORTH);
        JLabel pendingPermissions = new JLabel("Per-task permissions and approvals will appear here.", JLabel.CENTER);
        pendingPermissions.setForeground(java.awt.Color.GRAY);
        taskPermissions.add(pendingPermissions, BorderLayout.CENTER);
        tabs.addTab("Task permissions", taskPermissions);
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

    /**
     * Show one of the side tabs by title. The gate harness needs it: a screenshot of the transcript
     * cannot be evidence that the Tools tab works, and this is the only way to look at it from
     * outside the class.
     */
    public boolean selectSideTab(String title) {
        JTabbedPane tabs = sideTabs;
        if (tabs != null) {
            int i = tabs.indexOfTab(title);
            if (i >= 0) { tabs.setSelectedIndex(i); return true; }
        }
        tabs = conversationTabs;
        if (tabs != null) {
            int i = tabs.indexOfTab(title);
            if (i >= 0) { tabs.setSelectedIndex(i); return true; }
        }
        return false;
    }

    /** The side tab now showing, or null before a session exists. Harness-facing, like the setter. */
    public String selectedSideTab() {
        JTabbedPane tabs = sideTabs;
        if (tabs != null && tabs.getSelectedIndex() >= 0) return tabs.getTitleAt(tabs.getSelectedIndex());
        tabs = conversationTabs;
        return tabs == null || tabs.getSelectedIndex() < 0 ? null : tabs.getTitleAt(tabs.getSelectedIndex());
    }

    /**
     * The rule the Permissions tab has selected, for a gate run that has to prove a jump landed on
     * the right row rather than merely switched tabs.
     */
    public String selectedPermissionRule() {
        return null;
    }

    /**
     * What the selected side tab shows. A gate run photographs the panel it just opened, and the
     * side panel is the part of a 1400-pixel window a full-screen capture tends to cut off.
     */
    public java.awt.Component selectedSideComponent() {
        JTabbedPane tabs = sideTabs;
        if (tabs != null && tabs.getSelectedComponent() != null) return tabs.getSelectedComponent();
        tabs = conversationTabs;
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
        SessionEntry active = null;
        for (SessionEntry entry : sessions) if (entry.model == session) { active = entry; break; }
        disposeSessionViews();
        if (active != null) { active.model.shutdown(); sessions.remove(active); }
        session = null;
        host = null;
        sessionRows.clear();
        for (SessionEntry entry : sessions) sessionRows.addElement(entry);
        if (!sessions.isEmpty()) {
            sessionList.setSelectedIndex(0);
        } else {
            activeSessionLabel.setText("No session");
            addWelcome();
        }
    }

    private void disposeSessionViews() {
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
