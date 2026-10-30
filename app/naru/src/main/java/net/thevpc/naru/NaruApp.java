package net.thevpc.naru;

import net.thevpc.naru.impl.cmdline.NaruCmdLineProcessor;
import net.thevpc.nuts.app.NApplication;
import net.thevpc.nuts.app.NApp;
import net.thevpc.nuts.app.NAppRun;
import net.thevpc.nuts.platform.NEnv;

import java.util.Map;

/**
 * NARU — Nuts AI Reasoning Unit.
 *
 * <p>Plain Java entry point. The Nuts {@code NApplication} integration lives in
 * {@code NaruNutsApp} (a separate class) so this class has zero framework
 * dependencies and can run as a standalone fat-jar or be unit-tested without
 * the Nuts runtime on the classpath.
 *
 * <p>Usage:
 * <pre>
 *   java -jar naru.jar --task "Fix the bug in MyApp.java" --project-dir ./my-app
 * </pre>
 */
@NApp
public class NaruApp {

    public static void main(String[] args) {
        NApplication.builder(args).run();
    }

    @NAppRun
    public void run() {
        new NaruCmdLineProcessor(NApplication.of().cmdLine()).run();
    }
}

