package net.thevpc.naru.desktop;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;
import net.thevpc.swing.plaf.UIPlafManager;

import javax.swing.SwingUtilities;

/**
 * Launches the desktop cockpit.
 * <p>
 * Two rules from the plan govern this entry point. {@code prepareUI(args)} comes first, before any
 * frame exists, because HiDPI and font scaling cannot be retrofitted onto a built component tree.
 * And the workspace is never installed as the shared instance here — the console launcher does
 * that to own the process. Each desktop session keeps an explicit workspace and task hierarchy.
 * <p>
 * Full packaging — {@code @NAppInstall} launchers, {@code -c/--console} escape, splash,
 * single-instance guard — is Phase 5. This is the smallest thing that runs the v1 UI.
 */
public final class NaruDesktop {

    private NaruDesktop() {
    }

    public static void main(String[] args) {
        // the workspace must exist before prepareUI resolves anything through Nuts
        Nuts.require();
        NSwingUtils.prepareUI(args);
        UIPlafManager.getCurrentManager().apply("FlatLight");
        UIPlafManager.getCurrentManager().apply("FlatDark");
        NPath project = parseProject(args);
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow();
            w.setSize(1280, 800);
            w.setLocationRelativeTo(null);
            w.setVisible(true);
            if (project != null) {
                w.openProject(project);
            }
        });
    }

    private static NPath parseProject(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (("--project".equals(args[i]) || "-d".equals(args[i])) && i + 1 < args.length) {
                return NPath.of(args[i + 1]).toAbsolute();
            }
        }
        return null;
    }
}
