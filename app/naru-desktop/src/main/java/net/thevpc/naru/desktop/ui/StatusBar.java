package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.desktop.model.NaruSessionModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.Color;
import java.awt.Font;

/**
 * The status bar: model, task state, token counts and save state for the selected session.
 */
public final class StatusBar extends JPanel implements NaruSessionModel.Listener {

    private final NaruSessionModel session;
    private final JLabel status = new JLabel("not started");
    private final JLabel model = new JLabel();
    private final JLabel tasks = new JLabel();
    private final JLabel tokens = new JLabel();
    private final JLabel save = new JLabel();
    private final JLabel contextLabel = new JLabel("Context —");
    private final JProgressBar contextFill = new JProgressBar(0,100);
    private final JLabel quota = new JLabel("Quota —");
    private net.thevpc.naru.api.model.NaruModelKey lookedUpModel;
    private long lookedUpContextWindow = -1;
    private boolean contextLookupRunning;

    public StatusBar(NaruSessionModel session) {
        super();
        this.session = session;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        Color border = javax.swing.UIManager.getColor("Component.borderColor");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, border != null ? border : new Color(210, 215, 225)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        Font small = status.getFont().deriveFont(Font.PLAIN, 11.5f);
        Color muted = javax.swing.UIManager.getColor("Label.disabledForeground");
        if (muted == null) muted = new Color(130, 140, 150);
        for (JLabel l : new JLabel[]{status, model, tasks, tokens, save, contextLabel, quota}) {
            l.setFont(small);
            l.setForeground(muted);
        }
        add(status);
        add(sep());
        add(model);
        add(sep());
        add(Box.createHorizontalGlue());
        add(tasks);
        add(sep());
        add(contextLabel);
        add(contextFill);
        add(sep());
        add(quota);
        add(sep());
        add(tokens);
        add(sep());
        add(save);
        session.addListener(this);
        contextFill.setPreferredSize(new java.awt.Dimension(92, 10));
        contextFill.setStringPainted(false);
        contextFill.setToolTipText("Most recent request compared with the model context window");
        contextFill.setBorderPainted(false);
        contextFill.putClientProperty("ProgressBar.arc", 6);
        sessionModelChanged();
    }

    private static JLabel sep() {
        JLabel l = new JLabel("  ·  ");
        Color muted = javax.swing.UIManager.getColor("Label.disabledForeground");
        l.setForeground(muted != null ? muted : new Color(140, 150, 160));
        return l;
    }

    /** Report a state the session model never reaches, such as a project that failed to open. */
    public void setStatusText(String text) {
        status.setText(text);
    }

    public void dispose() {
        session.removeListener(this);
    }

    @Override
    public void sessionModelChanged() {
        status.setText(session.statusText());
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
        tokens.setText(session.tokensLabel());
        refreshContextUsage();
        refreshQuota();
        save.setText("saved " + session.saveState());
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
        if(used<0){contextLabel.setText("Context —");contextFill.setValue(0);contextFill.setToolTipText("Context usage is reported after the first model call");return;}
        if(limit>0){int percent=(int)Math.min(100,Math.round(100d*used/limit));contextLabel.setText("Context "+percent+"%");contextFill.setValue(percent);contextFill.setToolTipText(String.format(java.util.Locale.ROOT,"Most recent request: %,d / %,d context tokens",used,limit));
            Color fill=percent>=90?new Color(205,75,65):percent>=75?new Color(205,145,45):javax.swing.UIManager.getColor("Component.accentColor"); if(fill!=null)contextFill.setForeground(fill);
        }else{contextLabel.setText("Context "+String.format(java.util.Locale.ROOT,"%,d tokens",used));contextFill.setValue(0);contextFill.setToolTipText("Model context window size is unavailable");}
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
