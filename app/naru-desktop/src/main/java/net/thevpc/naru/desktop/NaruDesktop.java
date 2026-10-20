package net.thevpc.naru.desktop;

import net.thevpc.nuts.Nuts;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.swing.NSwingUtils;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Insets;
import java.util.prefs.Preferences;

/**
 * Launches the desktop cockpit.
 * <p>
 * Two rules from the plan govern this entry point. {@code prepareUI(args)} comes first, before any
 * frame exists, because HiDPI and font scaling cannot be retrofitted onto a built component tree.
 * The desktop owns one shared workspace for its lifetime. Each session still selects its own
 * project directory when it is created, while sharing the one extension registry and runtime.
 * <p>
 * Full packaging — {@code @NAppInstall} launchers, {@code -c/--console} escape, splash,
 * single-instance guard — is Phase 5. This is the smallest thing that runs the v1 UI.
 */
public final class NaruDesktop {
    private static final String DEFAULT_THEME = "com.formdev.flatlaf.FlatLightLaf";
    private static final String THEME_KEY = "lookAndFeelClass";
    private static final String FONT_SCALE_KEY = "desktop.fontScale";
    private static final String LAST_PROJECT_KEY = "desktop.lastProjectDirectory";
    private static java.awt.Font themeBaseFont;
    private static volatile NaruWorkspace workspace;

    private NaruDesktop() {
    }

    /** Return the single engine workspace owned by this desktop process. */
    public static NaruWorkspace workspace(NPath initialProjectDirectory) {
        NaruWorkspace current = workspace;
        if (current != null) return current;
        synchronized (NaruDesktop.class) {
            current = workspace;
            if (current == null) {
                workspace = current = NaruWorkspaceBuilder.of()
                        .projectDirectory(initialProjectDirectory)
                        .build();
            }
        }
        return current;
    }

    public static void main(String[] args) {
        // the workspace must exist before prepareUI resolves anything through Nuts
        Nuts.require();
        NSwingUtils.prepareUI(args);
        applyTheme(preferences().get(THEME_KEY, DEFAULT_THEME), false);
        NPath project = parseProject(args);
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow();
            w.setSize(1280, 800);
            w.setLocationRelativeTo(null);
            w.setVisible(true);
            initializeFontScale(w);
            String rememberedProject = preferences().get(LAST_PROJECT_KEY, null);
            NPath startupProject = project != null ? project
                    : rememberedProject == null || rememberedProject.isBlank()
                    ? NPath.of(System.getProperty("user.dir", ".")).toAbsolute()
                    : NPath.of(rememberedProject).toAbsolute();
            w.loadProjectCatalog(startupProject);
        });
    }

    public static void rememberProject(NPath project) {
        if (project != null) preferences().put(LAST_PROJECT_KEY, project.toAbsolute().toString());
    }

    private static Preferences preferences() { return Preferences.userNodeForPackage(NaruDesktop.class); }

    public static String selectedTheme() { return preferences().get(THEME_KEY, DEFAULT_THEME); }
    public static int fontScale() { return Math.max(80, Math.min(150, preferences().getInt(FONT_SCALE_KEY, 100))); }

    public static void setFontScale(int percent) {
        int value=Math.max(80,Math.min(150,percent)); if(value==fontScale()) return; preferences().putInt(FONT_SCALE_KEY,value);
        if(themeBaseFont==null) themeBaseFont=UIManager.getFont("defaultFont");
        applyScaledFontDefaults(100);
        for(java.awt.Window window:java.awt.Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window); scaleComponentFonts(window,value);
            syncFontScaleControl(window,value);
            if(window instanceof MainWindow main) main.refreshThemePalette();
            window.invalidate();window.validate();window.repaint();
        }
        applyScaledFontDefaults(value);
    }

    public static void applyTheme(String className) { applyTheme(className, true); }

    private static void applyTheme(String className, boolean persist) {
        try {
            UIManager.setLookAndFeel(className);
            themeBaseFont=UIManager.getFont("defaultFont"); if(themeBaseFont==null) themeBaseFont=UIManager.getFont("Label.font");
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("Button.margin", new Insets(4, 9, 4, 9));
            UIManager.put("Component.focusWidth", 1);
            applyScaledFontDefaults(100);
            if (persist) preferences().put(THEME_KEY, className);
            if (persist) for (java.awt.Window window : java.awt.Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
                clearFontBases(window); scaleComponentFonts(window,fontScale());
                if (window instanceof MainWindow main) main.refreshThemePalette();
                window.invalidate(); window.validate(); window.repaint();
            }
            applyScaledFontDefaults(fontScale());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not apply theme " + className + ": " + ex.getMessage(), ex);
        }
    }

    private static void applyScaledFontDefaults(int percent) {
        if(themeBaseFont!=null) UIManager.put("defaultFont",themeBaseFont.deriveFont(themeBaseFont.getSize2D()*percent/100f));
    }
    private static void clearFontBases(java.awt.Component c) {
        if(c instanceof javax.swing.JComponent j) j.putClientProperty("naru.baseFont",null);
        if(c instanceof java.awt.Container p) for(java.awt.Component child:p.getComponents()) clearFontBases(child);
    }
    public static void initializeFontScale(java.awt.Component c) { clearFontBases(c); scaleComponentFonts(c,fontScale()); }
    private static void scaleComponentFonts(java.awt.Component c,int percent) {
        if(c instanceof javax.swing.JComponent j && j.getFont()!=null) {
            java.awt.Font base=(java.awt.Font)j.getClientProperty("naru.baseFont");
            if(base==null){base=j.getFont();j.putClientProperty("naru.baseFont",base);}
            j.setFont(base.deriveFont(base.getSize2D()*percent/100f));
        }
        if(c instanceof java.awt.Container p) for(java.awt.Component child:p.getComponents()) scaleComponentFonts(child,percent);
    }
    private static void syncFontScaleControl(java.awt.Component c,int percent) {
        if(c instanceof javax.swing.JSlider s && "naru-font-scale".equals(s.getName()) && s.getValue()!=percent) s.setValue(percent);
        if(c instanceof java.awt.Container p) for(java.awt.Component child:p.getComponents()) syncFontScaleControl(child,percent);
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
