package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the open standard's reference validator ({@code skills-ref}, vendored under
 * {@code src/test/resources/skills-ref}) against sample skills, so NARU's behaviour is
 * pinned against the same verdicts the ecosystem publishes.
 * <p>
 * The reference validator is strict: it rejects missing descriptions, name mismatches, and
 * unknown front-matter fields. NARU is deliberately lenient (warn, never reject), so this
 * test only <em>observes</em> the reference verdicts and then cross-checks that a
 * well-formed standard skill is also read correctly by NARU's own loader.
 * <p>
 * The validator is a Python package with one third-party dependency (strictyaml). The test
 * bootstraps a throwaway virtualenv on first run (uv, then python3/venv as a fallback) and
 * skips with an assumption when neither the tooling nor the package network is available.
 */
@Timeout(120)
public class NaruSkillsStandardValidatorTest {

    private static final Path REFERENCE_ROOT = Path.of("src", "test", "resources", "skills-ref");

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private Path work;
    private Path python;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    public void bootstrapPython() throws Exception {
        work = Files.createTempDirectory("skills-ref-");
        python = venvPython(work);
        Assumptions.assumeTrue(python != null,
                "skills-ref venv could not be bootstrapped (python/uv or the package network is unavailable)");
    }

    @AfterEach
    public void tearDown() {
        // leave the venv cache alone; the OS temp dir is cleaned up by the platform
    }

    // ── python tooling ──────────────────────────────────────────────────────

    /**
     * Creates a virtualenv with {@code strictyaml} (the validator's only parsing
     * dependency) using uv, falling back to python3/venv. Null when neither works.
     */
    private static Path venvPython(Path work) {
        Path venv = work.resolve("venv");
        if (runOk("uv", "venv", "--seed", venv.toString())
                && runOk("uv", "pip", "install", "--python", venv.resolve("bin/python").toString(), "strictyaml>=1.7.3")) {
            return venv.resolve("bin/python");
        }
        Path py = venv.resolve("bin/python");
        if (runOk("python3", "-m", "venv", venv.toString())
                && runOk(py.toString(), "-m", "pip", "--quiet", "install", "strictyaml>=1.7.3")) {
            return py;
        }
        return null;
    }

    private static boolean runOk(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs the vendored reference validator on a skill directory, returning the list of
     * validation errors (empty = valid). Never throws: a parse failure inside the
     * validator surfaces as an error string, exactly as the library reports it.
     */
    private List<String> referenceValidate(Path skillDir) throws Exception {
        String script = "import sys;sys.path.insert(0,r'" + REFERENCE_ROOT.toAbsolutePath().resolve("src").toString()
                + "');from skills_ref.validator import validate;"
                + "[print('ERR:'+str(e)) for e in validate(sys.argv[1])]";
        Process p = new ProcessBuilder(python.toString(), "-c", script, skillDir.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("skills-ref validator hung: " + out);
        }
        List<String> errors = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (line.startsWith("ERR:")) {
                errors.add(line.substring(4));
            }
        }
        return errors;
    }

    private Path writeSkill(String dirName, String fileName, String frontMatter, String... body) throws Exception {
        Path dir = work.resolve("skills").resolve(dirName);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        if (frontMatter != null) {
            sb.append("---\n").append(frontMatter).append("\n---\n");
        }
        for (String line : body) {
            sb.append(line).append('\n');
        }
        Files.writeString(dir.resolve(fileName), sb.toString());
        return dir;
    }

    // ── reference verdicts ──────────────────────────────────────────────────

    @Test
    public void aWellFormedFolderSkillPassesTheReferenceValidator() throws Exception {
        Path dir = writeSkill("pdf-reader", "SKILL.md",
                "name: pdf-reader\ndescription: Read and extract text from PDF files",
                "# pdf-reader", "extracts text");

        List<String> errors = referenceValidate(dir);
        assertTrue(errors.isEmpty(), () -> "the reference validator must accept a well-formed skill: " + errors);
    }

    @Test
    public void lowercaseSkillMdIsAccepted() throws Exception {
        Path dir = writeSkill("lowcase", "skill.md",
                "name: lowcase\ndescription: lowercase file variant",
                "body");
        List<String> errors = referenceValidate(dir);

        assertTrue(errors.isEmpty(),
                () -> "the reference validator must accept skill.md: " + errors);
    }

    @Test
    public void aNameMismatchingItsFolderIsRejected() throws Exception {
        Path dir = writeSkill("pdf-reader", "SKILL.md",
                "name: other-name\ndescription: reads pdfs",
                "body");

        List<String> errors = referenceValidate(dir);
        assertTrue(errors.stream().anyMatch(e -> e.contains("must match")),
                () -> "the reference validator must reject a front-matter name that diverges from the folder: " + errors);
    }

    @Test
    public void aMissingDescriptionIsRejected() throws Exception {
        Path dir = writeSkill("bare", "SKILL.md", "name: bare", "body");

        List<String> errors = referenceValidate(dir);
        assertTrue(errors.stream().anyMatch(e -> e.contains("description")),
                () -> "the reference validator must demand a description: " + errors);
    }

    @Test
    public void unknownFrontMatterFieldsAreRejected() throws Exception {
        Path dir = writeSkill("extra", "SKILL.md",
                "name: extra\ndescription: extra field\nrequires: \"fs\"",
                "body");

        List<String> errors = referenceValidate(dir);
        // 'requires' is a NARU extension, not part of the open standard's field set
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unexpected fields")),
                () -> "the reference validator must reject NARU-specific fields: " + errors);
    }

    // ── NARU cross-check: same files, lenient loader ───────────────────────

    @Test
    public void naruReadsTheValidatedStandardSkillIdentically() throws Exception {
        Path tmp = Files.createTempDirectory("naru-validator-project-");
        Path md = tmp.resolve(".naru/skills/pdf-reader/SKILL.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, "---\nname: pdf-reader\ndescription: Read and extract text from PDF files\n---\n# pdf-reader\nextracts text\n");

        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.of(tmp));
        NaruSession session = new NaruSessionImpl(agent, NPath.of(tmp), null, true, NOOP_LISTENER, null, null, null);
        try {
            NaruSkill s = NaruSkillsExtension.skills(session).skills().findSkill("pdf-reader");
            assertNotNull(s, "naru must load the skill the reference validator accepted");
            // naru reports the same name, description, and layout — leniently, without warnings
            assertEquals("pdf-reader", s.getName());
            assertEquals("Read and extract text from PDF files", s.getDescription());
            assertEquals(NaruSkillLayout.FOLDER, s.getLayout());
            assertEquals(List.of("# pdf-reader", "extracts text"), s.getLines());
            assertEquals(List.of(), s.getWarnings());
            // no naru-extension fields here, so no requirement either
            assertEquals(null, s.getRequires());
        } finally {
            session.stop();
        }
    }
}