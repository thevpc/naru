//package net.thevpc.naru.desktop.smoke;
//
//import net.thevpc.naru.api.access.NaruAccessEffect;
//import net.thevpc.naru.api.access.NaruPermissionRule;
//import net.thevpc.naru.api.access.NaruPermissionStore;
//import net.thevpc.naru.api.agent.NaruInputRequest;
//import net.thevpc.naru.api.agent.NaruLogMode;
//import net.thevpc.naru.desktop.MainWindow;
//import net.thevpc.naru.desktop.model.NaruSessionModel;
//import net.thevpc.naru.desktop.model.PermissionsModel;
//import net.thevpc.naru.desktop.model.ToolCallModel;
//import net.thevpc.naru.desktop.model.TranscriptBlock;
//import net.thevpc.naru.desktop.util.PermissionsText;
//import net.thevpc.nuts.Nuts;
//import net.thevpc.nuts.io.NPath;
//import net.thevpc.nuts.swing.NSwingUtils;
//
//import javax.swing.SwingUtilities;
//import java.awt.Rectangle;
//import java.awt.Robot;
//import java.awt.image.BufferedImage;
//import java.io.File;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicLong;
//import javax.imageio.ImageIO;
//
///**
// * Phase 2 §6 gate run for the permissions editor: a denial that starts in a committed project
// * file has to end on the right row of the Permissions tab, with the real model, the real engine
// * policy and the real widgets in between.
// *
// * <p>Each hop is checked separately, so a failure names the hop rather than the run:
// * <ol>
// *   <li>a policy file committed by hand, before anything NARU wrote, is loaded into the live policy
// *       with source {@code perm:project} and is readable through the {@link NaruPermissionStore} the
// *       desktop binds to — the desktop has no parser of its own, so this is the extension's;</li>
// *   <li>a real {@code file_read} of the denied file is answered by the engine with a denial, and
// *       the desktop shows it as an untruncated card and a Tools row;</li>
// *   <li>the rule id is recoverable from that card text, its hit counter is in the pane, and
// *       {@link MainWindow#openPermissions(String)} lands the selection on that exact row;</li>
// *   <li>Save writes through the same API and Reload reads it back, so the committed file the user
// *       versions is the one the engine enforces.</li>
// * </ol>
// *
// * <p>A turn also reads an allowed file. It is a control, not a gate: it proves the rule is narrow
// * rather than a wildcard that happens to have caught the file we wanted blocked.
// *
// * <pre>
// * usage: PermissionsSmoke &lt;projectDir&gt; &lt;model&gt; [secondsToWait]
// *   -Dnaru.smoke.dump=&lt;file&gt;   write the whole transcript
// *   -Dnaru.smoke.control=false skip the allowed-file control turn
// * </pre>
// */
//public final class PermissionsSmoke {
//
//    /** The rule the harness writes, denies and then expects the jump to select. */
//    static final String RULE_ID = "smoke-deny-one-file";
//
//    /**
//     * The denied file. Named for what it holds rather than for being secret: a safety-tuned model
//     * asked to "read secret.txt and tell me what it contains" refuses instead of calling the tool,
//     * which was measured twice on runs 2 and 3 of this harness and is a refusal, not a desktop bug.
//     */
//    static final String DENIED_FILE = "data-1.txt";
//
//    /**
//     * The running session, kept so that a failure can be described rather than asserted: the
//     * report reads the live models and the transcript when a hop did not hold.
//     */
//    private static NaruSessionModel live;
//
//    /** The running window, for the same reason: a failed run still owes a screenshot. */
//    private static MainWindow liveWin;
//
//    public static void main(String[] args) throws Exception {
//        Nuts.require();
//        NSwingUtils.prepareUI(new String[0]);
//        NPath project = NPath.of(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"))
//                .toAbsolute();
//        String model = args.length > 1 ? args[1] : "qwen2.5-coder:14b";
//        long waitSeconds = args.length > 2 ? Long.parseLong(args[2]) : 420;
//        boolean control = !"false".equals(System.getProperty("naru.smoke.control"));
//
//        List<String> log = new ArrayList<>();
//        project.mkdirs();
//        project.resolve(DENIED_FILE).writeString("row 1 : widget-a : qty 3\nrow 2 : widget-b : qty 7\n");
//        project.resolve("notes.txt").writeString("a harmless note about the weather\n");
//
//        // Hop 1a: a committed policy file, written the way a human would commit one — as text, not
//        // through NARU at all. The desktop has no parser of its own any more: the format belongs to
//        // the naru-permissions extension, which the desktop reaches through NaruPermissionStore. So
//        // what this run has to prove is that a file from outside is read, enforced, edited, written
//        // back through that API and read again.
//        NPath file = project.resolve(NaruPermissionStore.PROJECT_FILE);
//        file.parent().mkdirs();
//        file.writeString("{ defaultEffect:allow, rules:[ { id:" + RULE_ID
//                + ", resourceType:file, patterns:[\"" + DENIED_FILE + "\"], actions:[read],"
//                + " effect:DENY, description:\"committed before the session booted\" } ] }");
//        log.add("committed policy at " + file);
//
//        AtomicLong maxPing = new AtomicLong();
//        AtomicInteger pings = new AtomicInteger();
//        AtomicInteger stallsOver16 = new AtomicInteger();
//        AtomicInteger stallsOver250 = new AtomicInteger();
//        Thread pinger = new Thread(() -> {
//            while (true) {
//                long t0 = System.nanoTime();
//                try {
//                    SwingUtilities.invokeAndWait(() -> {
//                    });
//                } catch (Exception e) {
//                    return;
//                }
//                long ms = (System.nanoTime() - t0) / 1_000_000;
//                maxPing.accumulateAndGet(ms, Math::max);
//                pings.incrementAndGet();
//                if (ms > 16) {
//                    stallsOver16.incrementAndGet();
//                }
//                if (ms > 250) {
//                    stallsOver250.incrementAndGet();
//                }
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException e) {
//                    return;
//                }
//            }
//        }, "edt-pinger");
//        pinger.setDaemon(true);
//        pinger.start();
//
//        MainWindow[] win = new MainWindow[1];
//        SwingUtilities.invokeAndWait(() -> {
//            MainWindow w = new MainWindow();
//            // the display is 1400x900; a window at 40,40 loses its side panel off the right edge
//            // and the side panel is the evidence
//            w.setSize(1380, 860);
//            w.setLocation(0, 0);
//            w.setVisible(true);
//            w.openProject(project);
//            win[0] = w;
//        });
//        liveWin = win[0];
//        long bootStart = System.currentTimeMillis();
//        NaruSessionModel session = awaitSession(win[0], 60);
//        long bootMillis = System.currentTimeMillis() - bootStart;
//        if (session == null || !session.isRunning()) {
//            report(false, "the session never started after " + bootMillis / 1000 + " s",
//                    project, model, log, pings.get(), maxPing.get(), stallsOver16.get(),
//                    stallsOver250.get(), 0, null);
//            return;
//        }
//        log.add("session booted in " + bootMillis / 1000 + " s");
//        live = session;
//        SwingUtilities.invokeAndWait(() -> session.transcript().setVerbose(true));
//
//        // hop 1b: the engine's loader put that file into the live policy, and the pane shows it
//        PermissionsModel[] pm = new PermissionsModel[1];
//        List<PermissionsModel.Row>[] rows = new List[1];
//        boolean[] tabOpens = new boolean[1];
//        SwingUtilities.invokeAndWait(() -> {
//            pm[0] = session.permissions();
//            rows[0] = pm[0].rows();
//            tabOpens[0] = win[0].selectSideTab("Permissions");
//        });
//        PermissionsModel model0 = pm[0];
//        PermissionsModel.Row rule = model0.row(RULE_ID);
//        log.add("permissions tab: attached=" + model0.isAttached() + " rows=" + rows[0].size()
//                + " default-effect=" + model0.defaultEffect() + " tab-selectable=" + tabOpens[0]);
//        if (rule == null) {
//            report(false, "the rule in the project file never reached the live policy the pane reads",
//                    project, model, log, pings.get(), maxPing.get(), stallsOver16.get(),
//                    stallsOver250.get(), 0, null);
//            return;
//        }
//        log.add("rule " + rule.id() + ": source=" + rule.rule().source()
//                + " effect=" + rule.rule().effect() + " patterns=" + rule.rule().patterns()
//                + " actions=" + rule.rule().actions() + " enabled=" + rule.rule().enabled());
//
//        // hop 1c: the store the desktop binds to is the extension's, reached through naru-api
//        NaruPermissionStore[] store = new NaruPermissionStore[1];
//        SwingUtilities.invokeAndWait(() -> {
//            store[0] = model0.projectStore();
//            if (store[0] != null) {
//                // read it through the API rather than trusting the projection
//                store[0].readProjectRules();
//            }
//        });
//        if (store[0] == null) {
//            report(false, "the desktop bound no project store: the naru-permissions extension was "
//                    + "not reachable through NaruPermissionStore.of(session)", project, model, log,
//                    pings.get(), maxPing.get(), stallsOver16.get(), stallsOver250.get(), 0, null);
//            return;
//        }
//        List<NaruPermissionRule> fromStore = store[0].readProjectRules();
//        log.add("project store: name=" + store[0].name() + " file=" + store[0].projectFile()
//                + " rules-read=" + fromStore.size() + " first-id="
//                + (fromStore.isEmpty() ? "none" : fromStore.get(0).id())
//                + " default-effect=" + store[0].readProjectDefaultEffect(NaruAccessEffect.ALLOW));
//        if (fromStore.size() != 1 || !RULE_ID.equals(fromStore.get(0).id())) {
//            report(false, "the store the desktop reaches does not read the committed file",
//                    project, model, log, pings.get(), maxPing.get(), stallsOver16.get(),
//                    stallsOver250.get(), 0, null);
//            return;
//        }
//
//        // hop 2: a real turn asks for the denied file
//        long deadline = System.currentTimeMillis() + waitSeconds * 1000;
//        long[] seen = {0};
//        NaruInputRequest r = awaitNewRequest(session, seen, deadline);
//        if (r == null) {
//            report(false, "the session never parked on its first prompt", project, model, log,
//                    pings.get(), maxPing.get(), stallsOver16.get(), stallsOver250.get(), 0, null);
//            return;
//        }
//        submit(session, "/model use " + model);
//        awaitQuiet(session, seen, Math.min(deadline, System.currentTimeMillis() + 90_000), log);
//        int callsBefore = rowCount(session);
//        long denyStart = System.currentTimeMillis();
//        // Two phrasings for the same turn. Runs 2 and 3 of this harness timed out here and the
//        // post-mortem showed the model answering "I'm sorry, but I can't assist with that request"
//        // rather than calling a tool — a refusal of the prompt, not a desktop defect. The hop under
//        // test is "a denied read becomes a tool row", which does not depend on the wording.
//        String[] prompts = {
//                "Call the file_read tool with path " + DENIED_FILE + " and read the file, "
//                        + "then tell me what it contains. Do not use any other tool.",
//                "This project contains a data file named " + DENIED_FILE + ". Call the file_read "
//                        + "tool with that path and report its first row. Do not use any other tool.",
//        };
//        ToolCallModel.Row denial = null;
//        for (int attempt = 0; attempt < prompts.length; attempt++) {
//            if (attempt > 0) {
//                log.add("turn attempt 1 got no denial after "
//                        + (System.currentTimeMillis() - denyStart) / 1000 + " s; the model answered: "
//                        + oneLine(lastResponse(session), 200) + " — retrying");
//                awaitQuiet(session, seen, Math.min(deadline, System.currentTimeMillis() + 45_000),
//                        log);
//            }
//            submit(session, prompts[attempt]);
//            long slice = deadline - System.currentTimeMillis();
//            long until = attempt == 0
//                    ? System.currentTimeMillis() + Math.max(120_000, slice / 2) : deadline;
//            denial = awaitDenial(session, Math.min(deadline, until));
//            if (denial != null) {
//                break;
//            }
//        }
//        long denyMillis = System.currentTimeMillis() - denyStart;
//        if (denial == null) {
//            report(false, "no permission-denied tool result appeared within " + denyMillis / 1000
//                    + " s (file_read was never blocked, or never called)", project, model, log,
//                    pings.get(), maxPing.get(), stallsOver16.get(), stallsOver250.get(), 0, null);
//            return;
//        }
//        log.add("denial after " + denyMillis / 1000 + " s: tool=" + denial.toolName()
//                + " status=" + denial.status() + " result-chars=" + denial.resultChars()
//                + " calls-seen-this-turn=" + (rowCount(session) - callsBefore));
//        String deniedText = denial.result() == null ? "" : denial.result().toString();
//        String parsedId = PermissionsText.matchedRuleId(deniedText);
//        log.add("denied row text: " + oneLine(deniedText, 300));
//
//        // hop 3: the same denial is a full card in the transcript, the rule id is recoverable
//        // from it, and its counter is in the pane
//        StringBuilder cards = new StringBuilder();
//        SwingUtilities.invokeAndWait(() -> {
//            for (TranscriptBlock b : session.transcript().toolCards()) {
//                if (PermissionsText.isDenial(b.text())) {
//                    cards.append(b.text()).append('\n');
//                }
//            }
//        });
//        String cardText = cards.toString();
//        String cardRuleId = PermissionsText.matchedRuleId(cardText);
//        int longestDenial = longestDenialLine(cardText);
//        boolean shownWhole = !deniedText.isEmpty() && cardText.contains(deniedText);
//        // the counter is carried to the screen by the session's 2 Hz poll — a decision is counted on
//        // the worker that asked the policy and never painted from there — so the honest wait is one
//        // poll plus slack, not a read in the same millisecond as the denial
//        PermissionsModel.Row counted = awaitCounted(model0, RULE_ID, 5_000);
//        if (counted == null) {
//            report(false, "the permissions pane has no row for " + RULE_ID + " after the denial",
//                    project, model, log, pings.get(), maxPing.get(), stallsOver16.get(),
//                    stallsOver250.get(), 0, null);
//            return;
//        }
//        log.add("denial cards: chars=" + cardText.length() + " longest-line=" + longestDenial
//                + " result-shown-whole-in-a-card=" + shownWhole
//                + " matched-rule-on-card=" + cardRuleId + " pane-hits=" + counted.hits()
//                + " pane-blocks=" + counted.blocks() + " policy-decisions=" + model0.totalDecisions());
//
//        boolean jumped = false;
//        String jumpTarget = cardRuleId != null ? cardRuleId : parsedId;
//        if (jumpTarget != null) {
//            SwingUtilities.invokeAndWait(() -> win[0].openPermissions(jumpTarget));
//            String tab = readString(win[0]::selectedSideTab);
//            String selected = readString(win[0]::selectedPermissionRule);
//            // A jump that selects a row in a panel pushed off the right edge of the window is not a
//            // jump anyone can follow, so the geometry is part of the claim, not a detail.
//            final boolean[] reachable = new boolean[1];
//            final String[] geometry = new String[1];
//            SwingUtilities.invokeAndWait(() -> {
//                java.awt.Component c = win[0].selectedSideComponent();
//                if (c == null) {
//                    geometry[0] = "no side component";
//                    return;
//                }
//                java.awt.Rectangle box = c.getBounds();
//                java.awt.Point onScreen = c.getLocationOnScreen();
//                java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
//                reachable[0] = c.isShowing() && box.width >= 240
//                        && onScreen.x >= 0 && onScreen.x + box.width <= screen.width;
//                geometry[0] = "x=" + onScreen.x + " width=" + box.width
//                        + " showing=" + c.isShowing() + " screen-width=" + screen.width;
//            });
//            jumped = "Permissions".equals(tab) && jumpTarget.equals(selected) && reachable[0];
//            log.add("jump: target=" + jumpTarget + " tab=" + tab + " selected-row=" + selected
//                    + " on-screen=" + reachable[0] + " geometry=" + geometry[0]
//                    + " landed=" + jumped);
//            // photographed here rather than at the end: hop 4 disables and re-enables the rule, and a
//            // repaint after that is no longer the moment the jump landed
//            File jumpedShot = capturePane(win[0], "naru-permissions-jump");
//            log.add("jump screenshot=" + (jumpedShot == null ? "none" : jumpedShot.getAbsolutePath()));
//        } else {
//            log.add("jump: no rule id parseable from the denial text ("
//                    + PermissionsText.DENIAL_PREFIX + " present="
//                    + PermissionsText.isDenial(deniedText) + ")");
//        }
//
//        // hop 4: Save and Reload, which is the desktop writing a committed file through the
//        // extension's store and reading it back. Disable the rule first so the file content differs
//        // from what booted the session; an enabled rule is the default and would round-trip to the
//        // same bytes.
//        boolean saved = false;
//        boolean reloaded = false;
//        SwingUtilities.invokeAndWait(() -> model0.setEnabled(RULE_ID, false));
//        String saveMessage;
//        try {
//            saveMessage = readString(() -> model0.saveToProject());
//        } catch (Exception e) {
//            saveMessage = "threw " + e;
//        }
//        List<NaruPermissionRule> afterSave = readStore(store[0]);
//        saved = saveMessage != null && !saveMessage.startsWith("the naru-permissions")
//                && afterSave.size() == 1 && !afterSave.get(0).enabled();
//        log.add("save: " + oneLine(saveMessage, 120) + " -> file rules=" + afterSave.size()
//                + " enabled=" + (afterSave.isEmpty() ? "?" : afterSave.get(0).enabled()));
//        // put the file back the way it was, then prove a reload re-enforces it
//        List<NaruPermissionRule> reEnabled = new ArrayList<>();
//        for (NaruPermissionRule savedRule : afterSave) {
//            reEnabled.add(savedRule.enabled(true));
//        }
//        String restore = store[0].writeProjectRules(reEnabled, NaruAccessEffect.ALLOW);
//        String reloadMessage;
//        try {
//            reloadMessage = readString(() -> model0.reloadFromProject());
//        } catch (Exception e) {
//            reloadMessage = "threw " + e;
//        }
//        PermissionsModel.Row afterReload = model0.row(RULE_ID);
//        reloaded = restore == null && afterReload != null && afterReload.rule().enabled();
//        log.add("reload: " + oneLine(reloadMessage, 120) + " -> rule enabled again=" + reloaded);
//
//        // the control turn: an allowed file must still read, otherwise the rule is a wildcard
//        String controlOutcome = "skipped";
//        if (control) {
//            int before = rowCount(session);
//            awaitQuiet(session, seen, Math.min(deadline, System.currentTimeMillis() + 60_000), log);
//            submit(session, "Call the file_read tool with path notes.txt and tell me its first line. "
//                    + "Do not use any other tool.");
//            ToolCallModel.Row ok = awaitAllowedRead(session, deadline);
//            int after = rowCount(session);
//            if (ok == null) {
//                controlOutcome = "no file_read of notes.txt reached the panel (rows +"
//                        + (after - before) + ")";
//            } else if (PermissionsText.isDenial(ok.result() == null ? "" : ok.result().toString())) {
//                controlOutcome = "FAILED: the rule also blocked notes.txt";
//                jumped = false;
//            } else {
//                controlOutcome = "allowed, status=" + ok.status() + " chars=" + ok.resultChars();
//            }
//            log.add("control turn (read notes.txt): " + controlOutcome);
//        }
//
//        String dumpPath = System.getProperty("naru.smoke.dump");
//        if (dumpPath != null) {
//            dump(session, NPath.of(dumpPath));
//        }
//        SwingUtilities.invokeAndWait(() -> win[0].selectSideTab("Permissions"));
//        File shot = capture(win[0]);
//        File paneShot = capturePane(win[0]);
//        log.add("screenshot=" + (shot == null ? "none" : shot.getAbsolutePath())
//                + " permissions-pane=" + (paneShot == null ? "none" : paneShot.getAbsolutePath()));
//
//        boolean ok = rule.rule().effect() == NaruAccessEffect.DENY
//                && NaruPermissionStore.SOURCE_PROJECT.equals(rule.rule().source())
//                && denial.status() == ToolCallModel.Status.FAILED
//                && shownWhole
//                && RULE_ID.equals(cardRuleId)
//                && counted.blocks() > 0
//                && jumped
//                && saved
//                && reloaded
//                && !controlOutcome.startsWith("FAILED");
//        String stats = win[0].host() == null ? "n/a" : win[0].host().statsLine();
//        int[] counts = counts(session);
//        report(ok, ok ? null : "see the detail lines", project, model, log, pings.get(),
//                maxPing.get(), stallsOver16.get(), stallsOver250.get(), counts[1], stats);
//        SwingUtilities.invokeAndWait(session::shutdown);
//        System.exit(ok ? 0 : 1);
//    }
//
//    // ── helpers ──────────────────────────────────────────────────────────────
//
//    /** The row as the pane shows it, once the 2 Hz poll has carried the counter across. */
//    private static PermissionsModel.Row awaitCounted(PermissionsModel m, String id, long millis)
//            throws Exception {
//        long until = System.currentTimeMillis() + millis;
//        PermissionsModel.Row row = null;
//        while (true) {
//            final PermissionsModel.Row[] out = new PermissionsModel.Row[1];
//            SwingUtilities.invokeAndWait(() -> out[0] = m.row(id));
//            row = out[0];
//            if (row != null && row.blocks() > 0) {
//                return row;
//            }
//            if (System.currentTimeMillis() >= until) {
//                return row;
//            }
//            Thread.sleep(250);
//        }
//    }
//
//    private static void submit(NaruSessionModel s, String line) throws Exception {
//        SwingUtilities.invokeAndWait(() -> s.submit(line));
//    }
//
//    private static int rowCount(NaruSessionModel s) throws Exception {
//        final int[] out = new int[1];
//        SwingUtilities.invokeAndWait(() -> out[0] = s.tools().size());
//        return out[0];
//    }
//
//    /** The first panel row whose result is the engine's denial shape. */
//    private static ToolCallModel.Row awaitDenial(NaruSessionModel s, long deadline)
//            throws Exception {
//        while (System.currentTimeMillis() < deadline) {
//            final ToolCallModel.Row[] hit = new ToolCallModel.Row[1];
//            SwingUtilities.invokeAndWait(() -> {
//                for (ToolCallModel.Row r : s.tools().rows()) {
//                    if (PermissionsText.isDenial(r.result() == null ? "" : r.result().toString())) {
//                        hit[0] = r;
//                    }
//                }
//            });
//            if (hit[0] != null) {
//                return hit[0];
//            }
//            Thread.sleep(1000);
//        }
//        return null;
//    }
//
//    /** The first panel row that read {@code notes.txt} and settled. */
//    private static ToolCallModel.Row awaitAllowedRead(NaruSessionModel s, long deadline)
//            throws Exception {
//        while (System.currentTimeMillis() < deadline) {
//            final ToolCallModel.Row[] hit = new ToolCallModel.Row[1];
//            SwingUtilities.invokeAndWait(() -> {
//                for (ToolCallModel.Row r : s.tools().rows()) {
//                    if (!"file_read".equals(r.toolName())
//                            || r.status() == ToolCallModel.Status.RUNNING) {
//                        continue;
//                    }
//                    String args = String.valueOf(r.arguments());
//                    if (args.contains("notes.txt")) {
//                        hit[0] = r;
//                    }
//                }
//            });
//            if (hit[0] != null) {
//                return hit[0];
//            }
//            if (!s.isRunning()) {
//                return null;
//            }
//            Thread.sleep(1000);
//        }
//        return null;
//    }
//
//    /** The longest denial line in the card text — the number that says it is not the 300 cut. */
//    private static int longestDenialLine(String cardText) {
//        int best = 0;
//        for (String line : cardText.split("\n")) {
//            best = Math.max(best, line.length());
//        }
//        return best;
//    }
//
//    /** Read the committed file through the API the desktop binds to, never through a copy of it. */
//    private static List<NaruPermissionRule> readStore(NaruPermissionStore store) {
//        try {
//            return store.readProjectRules();
//        } catch (RuntimeException e) {
//            return List.of();
//        }
//    }
//
//    private static NaruSessionModel awaitSession(MainWindow w, int seconds) throws Exception {
//        for (int i = 0; i < seconds * 4; i++) {
//            NaruSessionModel s = w.session();
//            if (s != null && s.isRunning()) {
//                return s;
//            }
//            Thread.sleep(250);
//        }
//        return w.session();
//    }
//
//    private static NaruInputRequest awaitNewRequest(NaruSessionModel s, long[] seen, long deadline)
//            throws Exception {
//        while (System.currentTimeMillis() < deadline) {
//            final NaruInputRequest[] now = new NaruInputRequest[1];
//            final long[] count = new long[1];
//            SwingUtilities.invokeAndWait(() -> {
//                now[0] = s.pendingRequest();
//                count[0] = s.inputRequestsSeen();
//            });
//            if (now[0] != null && count[0] > seen[0]) {
//                seen[0] = count[0];
//                return now[0];
//            }
//            Thread.sleep(250);
//        }
//        return null;
//    }
//
//    /** Wait until the transcript stops growing for four seconds, or a new prompt parks. */
//    private static long awaitQuiet(NaruSessionModel s, long[] seen, long deadline, List<String> log)
//            throws Exception {
//        int last = -1;
//        long quietSince = System.currentTimeMillis();
//        while (System.currentTimeMillis() < deadline) {
//            int[] c = counts(s);
//            final long[] count = new long[1];
//            final boolean[] parked = new boolean[1];
//            SwingUtilities.invokeAndWait(() -> {
//                count[0] = s.inputRequestsSeen();
//                parked[0] = s.pendingRequest() != null;
//            });
//            if (c[1] != last) {
//                last = c[1];
//                quietSince = System.currentTimeMillis();
//            } else if (System.currentTimeMillis() - quietSince > 4000) {
//                return System.currentTimeMillis();
//            }
//            if (parked[0] && count[0] > seen[0]) {
//                seen[0] = count[0];
//                return System.currentTimeMillis();
//            }
//            Thread.sleep(1000);
//        }
//        log.add("note: the session never went quiet before the deadline");
//        return System.currentTimeMillis();
//    }
//
//    /** The tail of the assistant's last answer — so a refused turn is measured, not guessed. */
//    private static String lastResponse(NaruSessionModel s) throws Exception {
//        final String[] out = new String[1];
//        SwingUtilities.invokeAndWait(() -> {
//            String last = "";
//            for (TranscriptBlock b : s.transcript().blocks()) {
//                if (b.mode() == NaruLogMode.AGENT_RESPONSE) {
//                    last = b.text();
//                }
//            }
//            out[0] = last;
//        });
//        return out[0];
//    }
//
//    private static int[] counts(NaruSessionModel s) throws Exception {
//        final int[] out = new int[2];
//        SwingUtilities.invokeAndWait(() -> {
//            out[0] = s.transcript().totalBlocks();
//            out[1] = s.transcript().retainedChars();
//        });
//        return out;
//    }
//
//    private static String readString(java.util.function.Supplier<String> s) throws Exception {
//        final String[] out = new String[1];
//        SwingUtilities.invokeAndWait(() -> out[0] = s.get());
//        return out[0];
//    }
//
//    private static void dump(NaruSessionModel s, NPath file) throws Exception {
//        SwingUtilities.invokeAndWait(() -> {
//            StringBuilder sb = new StringBuilder();
//            for (TranscriptBlock b : s.transcript().blocks()) {
//                sb.append("── ").append(b.mode()).append(b.isClosed() ? " [closed]" : " [open]")
//                        .append(" ──\n").append(b.text()).append('\n');
//            }
//            try {
//                file.writeString(sb.toString());
//            } catch (Exception e) {
//                sb.append("(dump failed: ").append(e).append(")");
//            }
//        });
//        System.out.println("  transcript-dump=" + file);
//    }
//
//    private static File capture(MainWindow w) {
//        try {
//            BufferedImage img = new Robot().createScreenCapture(new Rectangle(1400, 900));
//            File f = new File(System.getProperty("java.io.tmpdir"), "naru-permissions-smoke.png");
//            ImageIO.write(img, "png", f);
//            return f;
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    /**
//     * Paint the Permissions tab itself, off-screen and at its own size. The window screenshot cuts
//     * the side panel on a 1400-pixel display, and the panel is the thing this run has to show.
//     */
//    private static File capturePane(java.awt.Component c) {
//        if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0) {
//            return null;
//        }
//        try {
//            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(),
//                    BufferedImage.TYPE_INT_ARGB);
//            java.awt.Graphics2D g = img.createGraphics();
//            try {
//                c.paint(g);
//            } finally {
//                g.dispose();
//            }
//            File f = new File(System.getProperty("java.io.tmpdir"),
//                    "naru-permissions-pane.png");
//            ImageIO.write(img, "png", f);
//            return f;
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    /** Paint the selected side tab by name, so two shots of it can coexist. */
//    private static File capturePane(MainWindow w, String name) {
//        java.awt.Component[] target = new java.awt.Component[1];
//        try {
//            SwingUtilities.invokeAndWait(() -> target[0] = w.selectedSideComponent());
//        } catch (Exception e) {
//            return null;
//        }
//        java.awt.Component c = target[0];
//        File shot = capturePane(c);
//        if (shot == null) {
//            return null;
//        }
//        File named = new File(System.getProperty("java.io.tmpdir"), name + ".png");
//        return shot.renameTo(named) ? named : shot;
//    }
//
//    private static File capturePane(MainWindow w) {
//        return capturePane(w, "naru-permissions-pane");
//    }
//
//    private static void report(boolean ok, String detail, NPath project, String model,
//                               List<String> log, int pings, long maxPing, int over16, int over250,
//                               int chars, String stats) {
//        System.out.println("SMOKE permissions");
//        System.out.println("  project=" + project);
//        System.out.println("  model=" + model);
//        System.out.println("  edt-pings=" + pings + " max-ping-ms=" + maxPing
//                + " stalls-over-16ms=" + over16 + " stalls-over-250ms=" + over250);
//        System.out.println("  transcript-retained-chars=" + chars + " host-stats=" + stats);
//        for (String l : log) {
//            System.out.println("  " + l);
//        }
//        if (detail != null) {
//            System.out.println("  failure: " + detail);
//        }
//        if (!ok) {
//            postMortem();
//        }
//        System.out.println("  VERDICT=" + (ok
//                ? "PASS — project file → policy → denial card → permissions row, end to end"
//                : "FAIL — see the detail above"));
//    }
//
//    /**
//     * What the models actually hold when a hop failed — which tool rows exist, what the session is
//     * parked on, and the tail of the transcript — plus the dump, so a FAIL names the hop instead of
//     * leaving the run to be re-guessed.
//     */
//    private static void postMortem() {
//        NaruSessionModel s = live;
//        if (s == null) {
//            System.out.println("  post-mortem: the session never booted");
//            return;
//        }
//        try {
//            SwingUtilities.invokeAndWait(() -> {
//                List<ToolCallModel.Row> rows = s.tools().rows();
//                System.out.println("  post-mortem: tool rows=" + rows.size()
//                        + " transcript blocks=" + s.transcript().totalBlocks()
//                        + " chars=" + s.transcript().retainedChars()
//                        + " requests-seen=" + s.inputRequestsSeen()
//                        + " parked=" + (s.pendingRequest() != null));
//                for (ToolCallModel.Row r : rows) {
//                    System.out.println("    call " + r.toolName() + " " + r.status() + " chars="
//                            + r.resultChars() + " :: " + oneLine(r.result() == null
//                            ? "" : r.result().toString(), 120));
//                }
//                if (s.pendingRequest() != null) {
//                    System.out.println("    parked on: " + oneLine(net.thevpc.naru.desktop.util
//                            .MsgText.plain(s.pendingRequest().prompt() == null
//                                    ? "" : s.pendingRequest().prompt().toString()), 160));
//                }
//                List<TranscriptBlock> blocks = s.transcript().blocks();
//                for (int i = Math.max(0, blocks.size() - 8); i < blocks.size(); i++) {
//                    TranscriptBlock b = blocks.get(i);
//                    System.out.println("    block[" + i + "] " + b.mode()
//                            + (b.isClosed() ? "" : " [open]") + " :: "
//                            + oneLine(b.text(), 160));
//                }
//                for (PermissionsModel.Row r : s.permissions().rows()) {
//                    System.out.println("    rule " + r.id() + " source=" + r.rule().source()
//                            + " effect=" + r.rule().effect() + " enabled=" + r.rule().enabled()
//                            + " hits=" + r.hits() + " blocks=" + r.blocks());
//                }
//            });
//            String dumpPath = System.getProperty("naru.smoke.dump");
//            if (dumpPath != null) {
//                dump(s, NPath.of(dumpPath));
//            }
//            MainWindow w = liveWin;
//            if (w != null) {
//                SwingUtilities.invokeAndWait(() -> w.selectSideTab("Permissions"));
//                System.out.println("  post-mortem screenshot=" + capture(w)
//                        + " permissions-pane=" + capturePane(w));
//            }
//        } catch (Exception e) {
//            System.out.println("  post-mortem failed: " + e);
//        }
//    }
//
//    private static String oneLine(String s, int max) {
//        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
//        return x.length() > max ? x.substring(0, max) + "…" : x;
//    }
//}
