package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;

/**
 * The status bar: model, task state, token counts and save state for the selected session.
 */
public final class StatusBar extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    private final StatusDot sessionDot = new StatusDot();
    private final JLabel sessionName = new JLabel("No session");
    private final JLabel status = new JLabel("not started");
    private final JLabel model = new JLabel();
    private final JLabel tasks = new JLabel();
    private final JLabel tokens = new JLabel();
    private final JLabel save = new JLabel();
    private final JLabel contextLabel = new JLabel("context —");
    private final ContextRing contextRing = new ContextRing();
    private final JLabel quota = new JLabel("Quota —");
    private net.thevpc.naru.api.model.NaruModelKey lookedUpModel;
    private long lookedUpContextWindow = -1;
    private boolean contextLookupRunning;

    public StatusBar(NaruSessionModel session) {
        super();
        this.session = session;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiPalette.border()),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        for (JLabel l : new JLabel[]{status, model, tasks, tokens, save, contextLabel, quota}) {
            UiFonts.apply(l, UiFonts.SECONDARY, Font.PLAIN);
            l.setForeground(UiPalette.muted());
        }
        // the gauge has the row's font so that its diameter, which is measured from it, tracks the
        // interface size exactly the way the words beside it do
        UiFonts.apply(contextRing, UiFonts.SECONDARY, Font.PLAIN);
        // The session this window is reading is stated here rather than in the title bar: the reader
        // looks at the conversation above and the composer beside this row, and a heading over the
        // transcript repeated what nothing nearby was asking.
        UiFonts.apply(sessionName, UiFonts.SECONDARY, Font.BOLD);
        // no hue of its own: the dot beside it already carries the session's state, and a coloured
        // name would read as a second status the row has no room for
        sessionName.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        sessionDot.setFont(UiFonts.font(UiFonts.SECONDARY, Font.PLAIN));
        add(sessionDot);
        add(Box.createHorizontalStrut(6));
        add(sessionName);
        add(sep());
        add(status);
        add(sep());
        add(model);
        add(sep());
        add(Box.createHorizontalGlue());
        add(tasks);
        add(sep());
        // the gauge leads its caption: a reader learns to look at the ring, and the words beside it
        // only have to name the number, not draw a bar to show it
        add(contextRing);
        add(Box.createHorizontalStrut(5));
        add(contextLabel);
        add(sep());
        add(quota);
        add(sep());
        add(tokens);
        add(sep());
        add(save);
        session.addListener(this);
        contextRing.setToolTipText("Most recent request compared with the model context window");
        contextRing.getAccessibleContext().setAccessibleName("Context usage");
        sessionModelChanged();
    }

    private static JLabel sep() {
        JLabel l = new JLabel("  ·  ");
        l.setForeground(UiPalette.muted());
        return l;
    }

    /** Report a state the session model never reaches, such as a project that failed to open. */
    public void setStatusText(String text) {
        status.setText(text);
    }

    /**
     * Name the session this window is reading, and mark its state.
     *
     * <p>The title bar carried this until now. A heading above the transcript states what the
     * reader is already inside, and the row under the composer is where the eye is when it checks
     * whether the last turn finished.
     */
    public void setSessionIdentity(String name, java.awt.Color state, boolean pulsing) {
        sessionName.setText(name);
        sessionDot.setColor(state == null ? UiPalette.IDLE : state);
        sessionDot.setPulsing(pulsing);
    }

    public void dispose() {
        contextRing.dispose();
        session.removeListener(this);
    }

    @Override
    public void sessionModelChanged() {
        String state = session.statusText();
        status.setText(state);
        model.setText("model " + session.model());
        int blocked = 0;
        int active = 0;
        for (NaruSessionModel.TaskRow t : session.tasks()) {
            if (t.status() == NaruTaskStatus.BLOCKED_ON_INPUT) {
                blocked++;
            }
            if (!t.status().isCompleted()) {
                active++;
            }
        }
        tasks.setText(active + " task" + (active == 1 ? "" : "s")
                + (blocked > 0 ? " (" + blocked + " waiting)" : ""));
        // Colour in this row carries state or it stays out. Two of these labels can ask something of
        // the reader — a turn that failed, a task waiting for an answer — and take the same hues the
        // session list uses; the rest keep the muted grey so the row never looks like a traffic light.
        boolean failed = state != null && state.toLowerCase(java.util.Locale.ROOT).contains("fail");
        status.setForeground(failed ? UiPalette.ATTENTION : UiPalette.muted());
        tasks.setForeground(blocked > 0 ? UiPalette.PENDING : UiPalette.muted());
        // the ring's sweep only means "this number is current" while something is actually running
        contextRing.setLive(active > 0);
        tokens.setText(session.tokensLabel());
        refreshContextUsage();
        refreshQuota();
        save.setText("saved " + clock(session.saveState()));
    }

    /**
     * The save stamp as a wall clock. Today, the date is the one thing the reader already knows, so
     * only the time is shown; on an older day the date comes back, because then it is the point.
     */
    private static String clock(String localDateTime) {
        if (localDateTime == null || localDateTime.isBlank() || "—".equals(localDateTime)) {
            return "—";
        }
        try {
            java.time.LocalDateTime at = java.time.LocalDateTime.parse(localDateTime);
            java.time.format.DateTimeFormatter today = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
            java.time.format.DateTimeFormatter older = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm");
            return at.toLocalDate().equals(java.time.LocalDate.now())
                    ? today.format(at) : older.format(at);
        } catch (RuntimeException notATime) {
            return localDateTime;
        }
    }

    private void refreshContextUsage() {
        long used=session.latestPromptTokens();
        long limit=session.latestContextWindow()>0?session.latestContextWindow():lookedUpContextWindow;
        if (limit<=0 && session.latestUsageModel()!=null && !session.latestUsageModel().equals(lookedUpModel)
                && !contextLookupRunning && session.session()!=null) {
            lookedUpModel=session.latestUsageModel(); contextLookupRunning=true;
            var owner=session.session(); var key=lookedUpModel;
            new javax.swing.SwingWorker<Long,Void>() {
                protected Long doInBackground() { return owner.registry().modelsInfos(owner).stream().filter(i->i.key().equals(key)).map(i->i.capabilities()==null?-1L:i.capabilities().contextLength()).findFirst().orElse(-1L); }
                protected void done() { contextLookupRunning=false; try { lookedUpContextWindow=get(); } catch(Exception ignored) { lookedUpContextWindow=-1; } sessionModelChanged(); }
            }.execute();
        }
        if(used<0){contextLabel.setText("context —");contextRing.setFraction(ContextRing.UNKNOWN);contextRing.setToolTipText("Context usage is reported after the first model call");return;}
        if(limit>0){int percent=(int)Math.min(100,Math.round(100d*used/limit));contextLabel.setText("context "+percent+"%");contextRing.setFraction(percent/100d);contextRing.setToolTipText(String.format(java.util.Locale.ROOT,"Most recent request: %,d of %,d context tokens — %d%% of the window",used,limit,percent));
        }else{contextLabel.setText("context "+String.format(java.util.Locale.ROOT,"%,d tokens",used));contextRing.setFraction(ContextRing.UNKNOWN);contextRing.setToolTipText("Model context window size is unavailable");}
    }

    private void refreshQuota() {
        var infos=session.providerRateLimits();
        for(int i=infos.size()-1;i>=0;i--){var info=infos.get(i); for(var bucket:info.tokenBuckets()){
            Integer total=bucket.getLimit().orNull(), remaining=bucket.getRemaining().orNull();
            if(total!=null&&remaining!=null&&total>0){int used=Math.max(0,Math.min(100,100-(int)Math.round(100d*remaining/total))); String window=bucket.getWindow()==null?"":bucket.getWindow().name().toLowerCase(java.util.Locale.ROOT); quota.setText("Quota "+used+"% "+window); quota.setToolTipText(info.providerName()+" reported "+remaining+" of "+total+" tokens remaining in its "+window+" window"); return;}
        }}
        quota.setText("Quota —"); quota.setToolTipText("Provider quota information is not available");
    }
}
