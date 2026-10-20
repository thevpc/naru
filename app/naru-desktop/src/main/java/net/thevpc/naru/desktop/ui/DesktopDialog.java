package net.thevpc.naru.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** Shared undecorated, draggable dialog frame used by desktop workflows. */
public final class DesktopDialog {
    private DesktopDialog() {}

    public static JDialog create(Window owner, String title) {
        JDialog dialog = new JDialog(owner, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        JPanel shell = new JPanel(new BorderLayout());
        Color border = UIManager.getColor("Component.borderColor");
        if (border == null) border = UIManager.getColor("Separator.foreground");
        shell.setBorder(BorderFactory.createLineBorder(border == null ? Color.GRAY : border));
        JPanel header = new JPanel(new BorderLayout(8,0));
        header.setBorder(BorderFactory.createEmptyBorder(7,12,7,7));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JButton close = new JButton("×"); close.setFocusable(false); close.setMargin(new Insets(1,7,1,7)); close.setToolTipText("Close");
        close.putClientProperty("JButton.buttonType", "toolBarButton"); close.addActionListener(e -> dialog.dispose());
        header.add(heading,BorderLayout.CENTER); header.add(close,BorderLayout.EAST);
        Point[] drag = {null};
        header.addMouseListener(new java.awt.event.MouseAdapter(){ public void mousePressed(java.awt.event.MouseEvent e){ drag[0]=e.getPoint(); }});
        header.addMouseMotionListener(new java.awt.event.MouseMotionAdapter(){ public void mouseDragged(java.awt.event.MouseEvent e){ if(drag[0]!=null){ Point p=dialog.getLocation(); dialog.setLocation(p.x+e.getX()-drag[0].x,p.y+e.getY()-drag[0].y); } }});
        shell.add(header,BorderLayout.NORTH);
        dialog.setContentPane(shell);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().putClientProperty("naru.shell",shell);
        return dialog;
    }

    public static void setBody(JDialog dialog, Component body) {
        JPanel shell=(JPanel)dialog.getRootPane().getContentPane(); shell.add(body,BorderLayout.CENTER);
    }

    public static void showCentered(JDialog dialog, Component parent, int width, int height) {
        dialog.setSize(width,height); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }

    public static String promptText(Window owner,String title,String label,String initial) {
        JDialog dialog=create(owner,title); String[] result={null};
        JPanel body=new JPanel(new BorderLayout(8,12)); body.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
        JTextField value=new JTextField(initial==null?"":initial,30); body.add(new JLabel(label),BorderLayout.NORTH); body.add(value,BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0)); JButton cancel=new JButton("Cancel"),ok=new JButton("Save");
        cancel.addActionListener(e->dialog.dispose()); ok.addActionListener(e->{result[0]=value.getText();dialog.dispose();});
        buttons.add(cancel);buttons.add(ok);body.add(buttons,BorderLayout.SOUTH);setBody(dialog,body);
        dialog.getRootPane().setDefaultButton(ok);showCentered(dialog,owner,430,180);return result[0];
    }

    public static void showMessage(Window owner,String title,String message) {
        JDialog dialog=create(owner,title); JPanel body=new JPanel(new BorderLayout(8,12));body.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
        body.add(new JLabel("<html><div style='width:320px'>"+message+"</div></html>"),BorderLayout.CENTER);
        JButton ok=new JButton("OK");ok.addActionListener(e->dialog.dispose());JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(ok);body.add(buttons,BorderLayout.SOUTH);setBody(dialog,body);
        dialog.getRootPane().setDefaultButton(ok);showCentered(dialog,owner,390,150);
    }

    public static boolean confirm(Window owner,String title,String message,String acceptLabel) {
        JDialog dialog=create(owner,title); boolean[] result={false};
        JPanel body=new JPanel(new BorderLayout(8,12));body.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
        body.add(new JLabel("<html><div style='width:360px'>"+message.replace("\n","<br>")+"</div></html>"),BorderLayout.CENTER);
        JButton cancel=new JButton("Cancel"),accept=new JButton(acceptLabel);cancel.addActionListener(e->dialog.dispose());accept.addActionListener(e->{result[0]=true;dialog.dispose();});
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));buttons.add(cancel);buttons.add(accept);body.add(buttons,BorderLayout.SOUTH);setBody(dialog,body);dialog.getRootPane().setDefaultButton(accept);showCentered(dialog,owner,440,190);return result[0];
    }

    public static boolean confirmComponent(Window owner,String title,Component content) {
        JDialog dialog=create(owner,title);boolean[] result={false};JPanel body=new JPanel(new BorderLayout(8,12));body.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));body.add(content,BorderLayout.CENTER);
        JButton cancel=new JButton("Cancel"),accept=new JButton("Save");cancel.addActionListener(e->dialog.dispose());accept.addActionListener(e->{result[0]=true;dialog.dispose();});
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));buttons.add(cancel);buttons.add(accept);body.add(buttons,BorderLayout.SOUTH);setBody(dialog,body);dialog.getRootPane().setDefaultButton(accept);
        Dimension pref=content.getPreferredSize();showCentered(dialog,owner,Math.max(460,pref.width+60),Math.max(300,pref.height+100));return result[0];
    }
}
