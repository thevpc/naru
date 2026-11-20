package net.thevpc.naru.desktop.util;

import javax.swing.SwingUtilities;

/**
 * The one rule plan §4.4 states out loud: view-models and widgets are EDT-confined, and a
 * mistake here shows up as a rendering race that is miserable to debug later.
 * <p>
 * {@link #check()} throws when Swing asserts are enabled (they are in tests and dev) and logs
 * once otherwise, so a production build cannot be killed by a stray off-thread call but the
 * violation is still reported.
 */
public final class EDT {

    /** Off-thread calls are fatal when true, logged once when false. */
    private static volatile boolean strict = Boolean.getBoolean("naru.desktop.edt.assert");

    private static volatile boolean logged;

    private EDT() {
    }

    public static void setStrict(boolean value) {
        strict = value;
    }

    public static boolean isEdt() {
        return SwingUtilities.isEventDispatchThread();
    }

    /** Call at the top of every mutator that touches widget state. */
    public static void check(String what) {
        if (isEdt()) {
            return;
        }
        if (strict) {
            throw new IllegalStateException(what + " must run on the EDT");
        }
        if (!logged) {
            logged = true;
            System.err.println("[naru-desktop] " + what + " called off the EDT; set "
                    + "-Dnaru.desktop.edt.assert=true to make this throw");
        }
    }

    /** Run on the EDT now if already there, otherwise post. */
    public static void invoke(Runnable body) {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
        } else {
            SwingUtilities.invokeLater(body);
        }
    }
}
