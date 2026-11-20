package net.thevpc.naru.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code /assess} through a real session, the way the console and the desktop app reach it.
 * The listing needs no model. {@link #liveRun()} spends real model time, so it only runs with
 * {@code -Dnaru.assess.model=ollama/<model>} (and optionally {@code -Dnaru.assess.task=<id>},
 * {@code -Dnaru.assess.scale=<x>}).
 */
public class AssessDirectiveIntegrationTest {

    private static String runDirective(String line, Duration timeout) {
        Nuts.require();
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.ofTempFolder("naru-assess-it"));
        agent.modelFilter(NaruTestModels.defaultFilter());
        Capture capture = new Capture();
        NaruSession[] holder = new NaruSession[1];
        assertTimeoutPreemptively(timeout, () -> {
            holder[0] = agent.newSession().interaction(capture).statements(line.split("\n")).build().start();
            holder[0].waitFor();
        });
        holder[0].stop();
        return capture.text();
    }

    @Test
    public void listShowsTheJavaReferenceTasks() {
        String out = runDirective("/assess list", Duration.ofMinutes(2));
        assertTrue(out.contains("java-ref"), out);
        assertTrue(out.contains("simple-fix-divide") && out.contains("expert-store"), out);
    }

    @Test
    public void runsListsEmptyOrExisting() {
        String out = runDirective("/assess runs", Duration.ofMinutes(2));
        assertTrue(out.contains("no assessment runs yet") || out.contains("java-ref"), out);
    }

    @Test
    public void toolsVisibleToTheModelUnderTheAssessSetup() {
        // the same setup lines AssessRunner sends before the prompt, then a tool listing
        String out = runDirective(String.join("\n",
                "/mode implement", "/tags enable fs", "/tags enable network", "/tools"), Duration.ofMinutes(2));
        System.out.println("TOOLS-OUTPUT>>>\n" + out + "\n<<<");
    }

    @Test
    @EnabledIfSystemProperty(named = "naru.assess.model", matches = ".+")
    public void liveRun() {
        String task = System.getProperty("naru.assess.task", "simple-fix-divide");
        String scale = System.getProperty("naru.assess.scale", "1");
        long minutes = Long.getLong("naru.assess.testTimeoutMinutes", 60);
        String out = runDirective("/assess run " + System.getProperty("naru.assess.model")
                + " --task=" + task + " --timeout-scale=" + scale + (Boolean.getBoolean("naru.assess.keep") ? " --keep" : ""),
                Duration.ofMinutes(minutes));
        System.out.println(out);
        assertTrue(out.contains("TOTAL"), out);
    }

    /** Collects everything the session writes, whatever the log mode. */
    static final class Capture implements NaruInteraction {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        String text() {
            return String.join("\n", lines);
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            request.cancel("test");
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            if (message != null) {
                lines.add(String.valueOf(message));
            }
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            if (fragment != null) {
                lines.add(String.valueOf(fragment));
            }
        }

        @Override
        public void close() {
        }
    }
}
