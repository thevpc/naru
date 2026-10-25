package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Full-client-area settings surface. Categories are a stable home for future preferences. */
public final class SettingsPane extends JPanel {
    public record Theme(String name, String className) {
        @Override public String toString() { return name; }
    }

    public static final List<Theme> THEMES = List.of(
            new Theme("FlatLaf Light", "com.formdev.flatlaf.FlatLightLaf"),
            new Theme("FlatLaf Dark", "com.formdev.flatlaf.FlatDarkLaf"),
            new Theme("FlatLaf IntelliJ", "com.formdev.flatlaf.FlatIntelliJLaf"),
            new Theme("FlatLaf Darcula", "com.formdev.flatlaf.FlatDarculaLaf"),
            new Theme("FlatLaf Mac Light", "com.formdev.flatlaf.themes.FlatMacLightLaf"),
            new Theme("FlatLaf Mac Dark", "com.formdev.flatlaf.themes.FlatMacDarkLaf"));

    public SettingsPane(String selectedTheme, Consumer<String> onThemeChanged, int selectedFontScale, IntConsumer onFontScaleChanged, Runnable back,
                        Supplier<NaruWorkspace> workspace, Supplier<NaruSession> liveSession) {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(22, 26, 24, 26));
        JPanel header = new JPanel(new BorderLayout(24, 0));
        JLabel title = new JLabel("Settings"); UiFonts.apply(title, UiFonts.TITLE, Font.BOLD);
        // Glyph only, and no fixed pixel box: a 190x36 minimum outlives every interface size the
        // slider allows, so the button either clips the words or floats in empty space.
        javax.swing.JButton close = new javax.swing.JButton("←"); close.setMargin(new java.awt.Insets(7,12,7,12)); close.setToolTipText("Back to workspace"); close.getAccessibleContext().setAccessibleName("Back to workspace"); close.addActionListener(e -> back.run());
        header.add(title, BorderLayout.WEST); header.add(close, BorderLayout.EAST);
        // the page already has its own padding; the header sits apart from the section card so the
        // back button never reads as one of the appearance controls
        JPanel headerWrap = new JPanel(new BorderLayout());
        headerWrap.add(header, BorderLayout.CENTER);
        headerWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        add(headerWrap, BorderLayout.NORTH);

        JPanel navigation = new JPanel(new BorderLayout());
        JList<String> sections = new JList<>(new String[]{"Appearance", "Input & shortcuts", "Workspace"});
        UiFonts.apply(sections, UiFonts.BODY, Font.PLAIN);
        // a row and a rail measured in pixels survive one interface size; both come from this list's
        // own font so the settings navigation grows with the words it holds
        sections.setSelectedIndex(0);
        sections.setFixedCellHeight(sections.getFontMetrics(sections.getFont()).getHeight() + 14);
        sections.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        navigation.add(sections, BorderLayout.NORTH);
        navigation.setPreferredSize(new java.awt.Dimension(
                Math.max(180, sections.getFontMetrics(sections.getFont())
                        .stringWidth("Input & shortcuts") + 56), 100));

        JPanel appearance = new JPanel(new BorderLayout(10, 12));
        Color borderColor = javax.swing.UIManager.getColor("Component.borderColor");
        appearance.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(borderColor == null ? new Color(120,120,120) : borderColor),
                BorderFactory.createEmptyBorder(20, 22, 22, 22)));
        JLabel heading = new JLabel("Appearance"); UiFonts.apply(heading, UiFonts.SUBTITLE, Font.BOLD);
        JLabel copy = new JLabel("Choose a comfortable theme. NARU remembers your choice for the next launch.");
        JComboBox<Theme> theme = new JComboBox<>(THEMES.toArray(Theme[]::new));
        // no pixel box: the row stretches the combo to its width anyway, and a fixed height clips
        // the theme name as soon as the interface size grows past one setting
        theme.setEditable(false);
        theme.setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8)); return this;
            }
        });
        for (Theme t : THEMES) if (t.className().equals(selectedTheme)) theme.setSelectedItem(t);
        theme.addActionListener(e -> { Theme t = (Theme) theme.getSelectedItem(); if (t != null) onThemeChanged.accept(t.className()); });
        JPanel selector = new JPanel(new BorderLayout(0,16));
        JPanel themeRow=new JPanel(new BorderLayout(18,0)); themeRow.add(new JLabel("Theme"),BorderLayout.WEST); themeRow.add(theme,BorderLayout.CENTER);
        JPanel fontRow=new JPanel(new BorderLayout(18,0));
        JLabel fontLabel=new JLabel("Interface size · "+selectedFontScale+"%");
        JSlider fontScale=new JSlider(net.thevpc.naru.desktop.NaruDesktop.MIN_FONT_SCALE, net.thevpc.naru.desktop.NaruDesktop.MAX_FONT_SCALE, selectedFontScale);
        fontScale.setName("naru-font-scale");fontScale.setMajorTickSpacing(25);fontScale.setMinorTickSpacing(5);fontScale.setPaintTicks(true);fontScale.setSnapToTicks(true);
        fontScale.addChangeListener(e->{fontLabel.setText("Interface size · "+fontScale.getValue()+"%");if(!fontScale.getValueIsAdjusting())onFontScaleChanged.accept(fontScale.getValue());});
        fontRow.add(fontLabel,BorderLayout.WEST);fontRow.add(fontScale,BorderLayout.CENTER);
        selector.add(themeRow,BorderLayout.NORTH);selector.add(fontRow,BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        JPanel texts = new JPanel(new BorderLayout(0, 6)); texts.add(heading, BorderLayout.NORTH); texts.add(copy, BorderLayout.SOUTH);
        content.add(texts, BorderLayout.NORTH); content.add(selector, BorderLayout.CENTER);
        appearance.add(content, BorderLayout.NORTH);
        // one card per section; the list is the switcher, so every row it offers must show
        // something real when selected
        CardLayout cards = new CardLayout();
        JPanel pageArea = new JPanel(cards);
        pageArea.add(appearance, "Appearance");
        JPanel shortcuts = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel shortcutsNote = new JLabel("Shortcuts are fixed in this build; a shortcut editor is planned.");
        UiFonts.apply(shortcutsNote, UiFonts.BODY, Font.PLAIN);
        shortcuts.add(shortcutsNote);
        pageArea.add(shortcuts, "Input & shortcuts");
        WorkspaceEnvPane envPane = new WorkspaceEnvPane(workspace, liveSession);
        envPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor == null ? new Color(120, 120, 120) : borderColor),
                BorderFactory.createEmptyBorder(20, 22, 22, 22)));
        pageArea.add(envPane, "Workspace");
        sections.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String chosen = sections.getSelectedValue();
                if (chosen != null) {
                    cards.show(pageArea, chosen);
                }
            }
        });
        JPanel page = new JPanel(new BorderLayout(22, 0)); page.add(navigation, BorderLayout.WEST); page.add(pageArea, BorderLayout.CENTER);
        add(page, BorderLayout.CENTER);
    }
}
