package net.thevpc.naru.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

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

    /**
     * Let a dialog be resized from its edges even though it is undecorated and so has no native
     * frame to grab. The same gesture the main window already offers: a press within a few pixels
     * of an edge starts a resize, and the pointer says which edge before the press.
     *
     * <p>An {@link AWTEventListener} is used instead of per-component listeners because the
     * pointer sits over the dialog's children almost everywhere along the edge; filtering by the
     * dialog the event belongs to keeps every other window out of it.
     */
    public static void makeResizable(JDialog dialog) {
        final int edge = 6;
        final int minWidth = 460;
        final int minHeight = 320;
        MouseAdapter resize = new MouseAdapter() {
            private int direction;
            private Point origin;
            private Rectangle bounds;

            @Override public void mouseMoved(MouseEvent e) { hover(e); }

            @Override public void mouseExited(MouseEvent e) {
                if (origin != null) return;
                direction = 0;
                Component src = e.getComponent();
                if (isResizeCursor(src.getCursor())) src.setCursor(null);
            }

            @Override public void mousePressed(MouseEvent e) {
                Point where = inside(e);
                direction = e.getButton() == MouseEvent.BUTTON1 && where != null
                        ? resizeDirection(where) : 0;
                origin = direction == 0 ? null : e.getLocationOnScreen();
                bounds = direction == 0 ? null : dialog.getBounds();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (origin == null) return;
                Point now = e.getLocationOnScreen();
                int dx = now.x - origin.x, dy = now.y - origin.y;
                Rectangle b = new Rectangle(bounds);
                if ((direction & 1) != 0) { b.x += dx; b.width -= dx; }
                if ((direction & 2) != 0) b.width += dx;
                if ((direction & 4) != 0) { b.y += dy; b.height -= dy; }
                if ((direction & 8) != 0) b.height += dy;
                if (b.width < minWidth) {
                    if ((direction & 1) != 0) b.x -= minWidth - b.width;
                    b.width = minWidth;
                }
                if (b.height < minHeight) {
                    if ((direction & 4) != 0) b.y -= minHeight - b.height;
                    b.height = minHeight;
                }
                dialog.setBounds(b);
            }

            @Override public void mouseReleased(MouseEvent e) {
                origin = null;
                hover(e);
            }

            private void hover(MouseEvent e) {
                if (origin != null) return;
                Point where = inside(e);
                int next = where == null ? 0 : resizeDirection(where);
                direction = next;
                Component src = e.getComponent();
                if (next != 0) {
                    src.setCursor(resizeCursor(next));
                    dialog.setCursor(resizeCursor(next));
                } else {
                    if (isResizeCursor(src.getCursor())) src.setCursor(null);
                    dialog.setCursor(Cursor.getDefaultCursor());
                }
            }

            private Point inside(MouseEvent e) {
                if (!dialog.isShowing()) return null;
                Point onScreen = e.getLocationOnScreen();
                Point here = dialog.getLocationOnScreen();
                return new Point(onScreen.x - here.x, onScreen.y - here.y);
            }

            private int resizeDirection(Point p) {
                int d = 0;
                if (p.x < edge) d |= 1; else if (p.x > dialog.getWidth() - edge) d |= 2;
                if (p.y < edge) d |= 4; else if (p.y > dialog.getHeight() - edge) d |= 8;
                return d;
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
                int type = c.getType();
                return type == Cursor.W_RESIZE_CURSOR || type == Cursor.E_RESIZE_CURSOR
                        || type == Cursor.N_RESIZE_CURSOR || type == Cursor.S_RESIZE_CURSOR
                        || type == Cursor.NW_RESIZE_CURSOR || type == Cursor.NE_RESIZE_CURSOR
                        || type == Cursor.SW_RESIZE_CURSOR || type == Cursor.SE_RESIZE_CURSOR;
            }
        };
        AWTEventListener listener = event -> {
            if (!(event instanceof MouseEvent e)) return;
            Component src = e.getComponent();
            if (src == null) return;
            Window owner = SwingUtilities.getWindowAncestor(src);
            if (owner != dialog && src != dialog) return;
            switch (e.getID()) {
                case MouseEvent.MOUSE_MOVED -> resize.mouseMoved(e);
                case MouseEvent.MOUSE_EXITED -> resize.mouseExited(e);
                case MouseEvent.MOUSE_PRESSED -> resize.mousePressed(e);
                case MouseEvent.MOUSE_DRAGGED -> resize.mouseDragged(e);
                case MouseEvent.MOUSE_RELEASED -> resize.mouseReleased(e);
                default -> { }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        dialog.setMinimumSize(new Dimension(minWidth, minHeight));
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
        });
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
