package net.thevpc.naru;

import net.thevpc.naru.impl.cmdline.NaruCmdLineProcessor;
import net.thevpc.nuts.app.NApp;
import net.thevpc.nuts.app.NAppDefinition;
import net.thevpc.nuts.app.NAppRunner;

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
@NAppDefinition
public class NaruApp {

    public static void main(String[] args) {
        NApp.builder(args).run();
    }

    @NAppRunner
    public void run() {
        new NaruCmdLineProcessor(NApp.of().cmdLine()).run();
    }
}

