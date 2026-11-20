package net.thevpc.naru.ext.assess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.thevpc.naru.ext.assess.AssessCheck.Verdict;

/**
 * The {@code java-ref} asset: a small Maven "ledger" library (money, accounts, statements,
 * CSV) shipped as classpath resources, with seven tasks across four tiers.
 *
 * <p>Layout under {@code java-ref/}: {@code project/} is the correct codebase; {@code seeds/<task>/}
 * overlays files that plant the task's defect; {@code hidden/<task>/} holds acceptance tests
 * the model never sees; {@code index.txt} lists every file (a jar cannot be listed).
 */
public final class JavaRefAsset implements AssessAsset {
    private static final String BASE = "/net/thevpc/naru/ext/assess/java-ref/";

    private static final String PREAMBLE = """
            You are working on a small Java 17 Maven library (a ledger: money, accounts, transactions,
            statements, CSV) in the CURRENT directory, which is the project root.

            ENVIRONMENT: there is no network, Maven works only offline. Always run Maven as
            'mvn -o -q test' (-o offline, -q quiet) with the shell tool; mvn and java are on the PATH.
            The shell tool returns 'EXIT_CODE=<code>' followed by the output.

            RULES: never modify or delete existing test files or pom.xml, and never weaken a test to
            make it pass. You may ADD new test files. Do not create files outside the current directory.
            Before you finish, run 'mvn -o -q test' yourself and make sure it succeeds. Do not ask
            questions: decide and act. When everything is green, reply with a short factual summary of
            what you changed, then stop.

            TASK:
            """;

    private final List<AssessTask> tasks;
    private final AssessBuild build = new MavenBuild();

    public JavaRefAsset() {
        List<AssessTask> t = new ArrayList<>();
        t.add(task("simple-fix-divide", AssessTier.SIMPLE, "Fix a failing unit test",
                "The unit test MoneyTest.divideRoundsHalfUp is failing. Find the cause in the main code and fix it. "
                        + "Money.divide must round half away from zero to the nearest cent.",
                List.of(minimalChange(Set.of("src/main/java/ledger/Money.java"), 20)), 35));
        t.add(task("simple-rename", AssessTier.SIMPLE, "Rename a method across files",
                "Rename the public static method Report.fmt(Money) to Report.format(Money) and update every caller "
                        + "in the project. Behaviour must not change.",
                List.of(textAbsent("old-name-gone", "no remaining use of fmt(", "src", "fmt(", 20)), 35));
        t.add(task("normal-balance-at", AssessTier.NORMAL, "Add a feature with tests",
                "Add a method 'public Money balanceAt(String accountId, java.time.LocalDate date)' to Ledger. It returns "
                        + "the account's balance counting only transactions dated on or before 'date' (same-day transactions "
                        + "are included). With no such transactions it returns zero in the account's currency. An unknown "
                        + "account throws IllegalArgumentException. Add JUnit 5 tests for it in a NEW test file.",
                List.of(addsTests(2, 20)), 35));
        t.add(task("normal-transfer", AssessTier.NORMAL, "Atomic transfer between accounts",
                "Add 'public void transfer(String fromId, String toId, Money amount, java.time.LocalDate date, String description)' "
                        + "to Ledger. It posts a debit (-amount) on 'fromId' and a credit (+amount) on 'toId', both with the given date "
                        + "and description, and each with a unique transaction id of your choosing (repeated transfers must not collide). "
                        + "It must throw IllegalArgumentException, posting NOTHING, when: an account is unknown, from==to, the amount "
                        + "is zero or negative, or the two accounts and the amount do not all share one currency. "
                        + "Add JUnit 5 tests in a NEW test file.",
                List.of(addsTests(3, 20)), 35));
        t.add(task("complex-statement-order", AssessTier.COMPLEX, "Diagnose and fix a reported bug",
                "Bug report: 'Account statements show wrong running balances when transactions were posted out of date order. "
                        + "Example: post -4.00 on 2024-03-01, then +10.00 on 2024-01-01, then +2.50 on 2024-02-01. The statement lists the "
                        + "rows by date but the balance column does not add up row by row.' The final balance must still equal "
                        + "Ledger.balance, and transactions on the same day must keep the order they were posted in. First write a "
                        + "failing JUnit 5 test in a NEW test file that reproduces the report, then fix the production code.",
                List.of(addsTests(1, 20)), 35));
        t.add(task("complex-multicurrency", AssessTier.COMPLEX, "Multi-currency net worth",
                """
                        Add multi-currency valuation, exactly with this API (new files in package ledger unless stated):
                        - interface ExchangeRates { java.math.BigDecimal rate(String from, String to); }
                        - class FixedRates implements ExchangeRates, with a public no-arg constructor and a fluent
                          'FixedRates with(String from, String to, java.math.BigDecimal rate)' that registers a direct rate.
                          rate(from,to): 1 when from equals to; the direct rate when registered; otherwise the INVERSE
                          of a registered opposite rate (1/rate, any sensible precision); otherwise IllegalArgumentException.
                        - in Money: 'public Money convertTo(String targetCurrency, ExchangeRates rates)' multiplies the cents by the
                          rate and rounds HALF_UP (away from zero) to whole cents, returning Money in the target currency.
                        - in Ledger: 'public Money netWorth(String currency, ExchangeRates rates)' sums every account's balance
                          converted to 'currency' (convert each account balance, then add). An account whose currency has no
                          rate to the target makes it throw IllegalArgumentException.
                        Add JUnit 5 tests in a NEW test file.""",
                List.of(addsTests(3, 20)), 35));
        t.add(task("expert-store", AssessTier.EXPERT, "Extract a storage layer and a CLI command",
                """
                        Refactor the ledger to separate storage from logic, then use it from the CLI. Behaviour of the existing API
                        must be unchanged.
                        - interface TransactionStore { void append(Transaction t); java.util.List<Transaction> all(); }
                        - class InMemoryTransactionStore implements TransactionStore (insertion order).
                        - class FileTransactionStore implements TransactionStore with constructor FileTransactionStore(java.nio.file.Path file):
                          persists in the CSV format of CsvExporter/CsvImporter (header + one line per transaction); a new instance on an
                          existing file sees everything already stored; a missing file means empty; append writes through immediately.
                        - Ledger gets a constructor Ledger(TransactionStore store); the no-arg constructor keeps working and uses an
                          in-memory store. Ledger must keep NO separate copy of the transactions: all reads go to the store.
                          Accounts are not persisted.
                        - Main gets a second command: 'balance <csvFile> <accountId>' that prints exactly Report.balanceLine for that
                          account (one line, nothing else). The existing 'statement' command keeps working.
                        Add JUnit 5 tests in a NEW test file.""",
                List.of(addsTests(4, 20)), 35));
        this.tasks = List.copyOf(t);
    }

    private static AssessTask task(String id, AssessTier tier, String title, String prompt, List<AssessCheck> extra, int hiddenPoints) {
        List<AssessCheck> checks = new ArrayList<>();
        checks.add(new AssessCheck("builds", "main and test code compile", 15, true, c -> {
            AssessProcess.Result r = c.compile();
            return r.ok() ? Verdict.pass() : Verdict.fail(r.timedOut() ? "compile timed out" : r.cause(4));
        }));
        checks.add(new AssessCheck("tests", "the project's own tests pass", 20, true, c -> {
            AssessProcess.Result r = c.test();
            return r.ok() ? Verdict.pass() : Verdict.fail(r.timedOut() ? "tests timed out" : r.cause(4));
        }));
        checks.add(new AssessCheck("hidden", "hidden acceptance tests pass", hiddenPoints, c -> {
            AssessBuild.HiddenOutcome h = c.hidden();
            if (h.total() == 0) {
                return Verdict.fail(h.detail());
            }
            double f = (double) h.passed() / h.total();
            return f >= 1.0 ? Verdict.pass() : Verdict.partial(f, h.passed() + "/" + h.total() + " " + h.detail());
        }));
        checks.add(new AssessCheck("protected", "existing tests and pom.xml untouched", 10, true, c -> {
            List<String> bad = new ArrayList<>();
            for (String p : c.baseline().keySet()) {
                if ((p.startsWith("src/test/") || p.equals("pom.xml")) && c.modified().contains(p)) {
                    bad.add(p);
                }
            }
            return bad.isEmpty() ? Verdict.pass() : Verdict.fail("modified: " + String.join(", ", bad));
        }));
        checks.addAll(extra);
        return new AssessTask(id, tier, title, PREAMBLE + prompt, List.copyOf(checks));
    }

    private static AssessCheck minimalChange(Set<String> allowed, int points) {
        return new AssessCheck("minimal-change", "only the faulty file is modified", points, true, c -> {
            List<String> extra = new ArrayList<>();
            for (String p : c.modified()) {
                if (!allowed.contains(p)) {
                    extra.add(p);
                }
            }
            for (String p : c.added()) {
                if (p.startsWith("src/main/")) {
                    extra.add(p + " (new)");
                }
            }
            return extra.isEmpty() ? Verdict.pass() : Verdict.fail("unexpected changes: " + String.join(", ", extra));
        });
    }

    private static AssessCheck textAbsent(String id, String description, String under, String needle, int points) {
        return new AssessCheck(id, description, points, true, c -> {
            List<String> hits = new ArrayList<>();
            try (Stream<Path> s = Files.walk(c.dir().resolve(under))) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    if (Files.readString(p).contains(needle)) {
                        hits.add(c.dir().relativize(p).toString());
                    }
                }
            } catch (IOException e) {
                return Verdict.fail("cannot scan: " + e.getMessage());
            }
            return hits.isEmpty() ? Verdict.pass() : Verdict.fail("still in " + String.join(", ", hits));
        });
    }

    private static final Pattern TEST_ANNOTATION = Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest)\\b");

    private static AssessCheck addsTests(int wanted, int points) {
        return new AssessCheck("own-tests", "adds at least " + wanted + " new test(s)", points, c -> {
            int count = 0;
            for (String p : c.added()) {
                if (p.startsWith("src/test/") && p.endsWith(".java")) {
                    count += countTests(c.dir().resolve(p));
                }
            }
            if (count >= wanted) {
                return Verdict.pass();
            }
            return count == 0 ? Verdict.fail("no new tests") : Verdict.partial((double) count / wanted, count + " new test(s), wanted " + wanted);
        });
    }

    static int countTests(Path file) {
        try {
            Matcher m = TEST_ANNOTATION.matcher(Files.readString(file));
            int n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public String id() {
        return "java-ref";
    }

    @Override
    public String description() {
        return "Java 17 Maven ledger library: bug fix, rename, feature, diagnosis, multi-file refactor";
    }

    @Override
    public List<AssessTask> tasks() {
        return tasks;
    }

    @Override
    public void prepare(AssessTask task, Path dir) throws IOException {
        copyTree("project/", dir);
        copyTree("seeds/" + task.id() + "/", dir);
    }

    @Override
    public AssessBuild build() {
        return build;
    }

    /** Copies every indexed resource under {@code prefix} into {@code dir}, dropping the prefix. */
    static void copyTree(String prefix, Path dir) throws IOException {
        for (String entry : index()) {
            if (!entry.startsWith(prefix)) {
                continue;
            }
            Path target = dir.resolve(entry.substring(prefix.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = JavaRefAsset.class.getResourceAsStream(BASE + entry)) {
                if (in == null) {
                    throw new IOException("missing resource " + entry);
                }
                Files.write(target, in.readAllBytes());
            }
        }
    }

    static List<String> index() {
        try (InputStream in = JavaRefAsset.class.getResourceAsStream(BASE + "index.txt")) {
            if (in == null) {
                throw new IllegalStateException("java-ref index.txt is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maven-based build, always offline. */
    static final class MavenBuild implements AssessBuild {
        @Override
        public AssessProcess.Result compile(Path dir) {
            return AssessProcess.run(dir, 300, List.of("mvn", "-o", "-q", "-B", "test-compile"));
        }

        @Override
        public AssessProcess.Result test(Path dir) {
            return AssessProcess.run(dir, 400, List.of("mvn", "-o", "-q", "-B", "test"));
        }

        @Override
        public HiddenOutcome hiddenTests(AssessTask task, Path dir) {
            Path copy = null;
            try {
                copy = Files.createTempDirectory("naru-assess-hidden-");
                copyWithoutBuildOutput(dir, copy);
                int declared = 0;
                for (String entry : index()) {
                    String prefix = "hidden/" + task.id() + "/";
                    if (entry.startsWith(prefix)) {
                        Path target = copy.resolve("src/test/java/ledger").resolve(entry.substring(prefix.length()));
                        Files.createDirectories(target.getParent());
                        try (InputStream in = JavaRefAsset.class.getResourceAsStream(BASE + entry)) {
                            Files.write(target, in.readAllBytes());
                        }
                        declared += countTests(target);
                    }
                }
                AssessProcess.Result r = AssessProcess.run(copy, 400,
                        List.of("mvn", "-o", "-q", "-B", "test", "-Dtest=*HiddenTest", "-Dsurefire.failIfNoSpecifiedTests=false"));
                int[] counts = surefireCounts(copy.resolve("target/surefire-reports"), "HiddenTest");
                int total = Math.max(counts[0], declared);
                int failed = counts[1] + counts[2];
                if (counts[0] == 0) {
                    return new HiddenOutcome(total, 0, r.timedOut() ? "timed out" : "did not compile or run: " + r.cause(4));
                }
                return new HiddenOutcome(total, Math.max(0, counts[0] - failed), failed == 0 ? "ok" : r.cause(4));
            } catch (IOException e) {
                return new HiddenOutcome(0, 0, "cannot prepare hidden run: " + e.getMessage());
            } finally {
                deleteQuietly(copy);
            }
        }

        private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*");

        /** Sums tests/failures/errors over the surefire XML reports whose file name contains {@code nameFilter}. */
        static int[] surefireCounts(Path reports, String nameFilter) throws IOException {
            int[] sum = new int[3];
            if (!Files.isDirectory(reports)) {
                return sum;
            }
            try (Stream<Path> s = Files.list(reports)) {
                for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().startsWith("TEST-")
                        && f.getFileName().toString().endsWith(".xml") && f.getFileName().toString().contains(nameFilter))::iterator) {
                    Matcher m = SUITE.matcher(Files.readString(p));
                    if (m.find()) {
                        sum[0] += attr(m.group(), "tests");
                        sum[1] += attr(m.group(), "failures");
                        sum[2] += attr(m.group(), "errors");
                    }
                }
            }
            return sum;
        }

        private static int attr(String tag, String name) {
            Matcher m = Pattern.compile("\\b" + name + "=\"(\\d+)\"").matcher(tag);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        private static void copyWithoutBuildOutput(Path from, Path to) throws IOException {
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String rel = from.relativize(p).toString().replace('\\', '/');
                    if (rel.equals("target") || rel.startsWith("target/") || rel.startsWith(".git")) {
                        continue;
                    }
                    Path t = to.resolve(rel);
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    } else {
                        Files.createDirectories(t.getParent());
                        Files.copy(p, t);
                    }
                }
            }
        }

        private static void deleteQuietly(Path dir) {
            if (dir == null) {
                return;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
