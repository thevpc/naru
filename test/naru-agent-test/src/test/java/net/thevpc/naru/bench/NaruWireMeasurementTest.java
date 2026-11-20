package net.thevpc.naru.bench;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.agent.NaruTestModels;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

public class NaruWireMeasurementTest {
    @Test
    public void testWireMeasurementMin() throws Exception {
        Nuts.require();
        Path tempDir = Files.createTempDirectory("naru-wire-measure-min");
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.of(tempDir.toString()));
        agent.modelFilter(NaruTestModels.defaultFilter());

        // naru-min: fs, write, exec, network minus unwanted tools
        agent.newSession()
                .statements(
                        "/model add mock-ollama --protocol=openai --url=http://127.0.0.1:8089/min/v1 --models=qwen3.8:32k",
                        "/model use mock-ollama/qwen3.8:32k",
                        "/tags enable fs write exec network",
                        "/tools exclude git_commit ollama_status ollama_start ollama_stop ollama_install ollama_ps routine_run routine_add_line maven_compile maven_test maven_package web_search",
                        "Reply with the single word OK."
                )
                .build()
                .start()
                .waitFor();

        System.out.println("Wire measurement (min) session finished.");
    }

    @Test
    public void testWireMeasurementGranted() throws Exception {
        Nuts.require();

        Path tempDir = Files.createTempDirectory("naru-wire-measure-granted");
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.of(tempDir.toString()));
        agent.modelFilter(NaruTestModels.defaultFilter());

        // naru-granted: standard benchmark tags
        agent.newSession()
                .statements(
                        "/model add mock-ollama --protocol=openai --url=http://127.0.0.1:8089/granted/v1 --models=qwen3.8:32k",
                        "/model use mock-ollama/qwen3.8:32k",
                        "/tags enable fs write exec network",
                        "Reply with the single word OK."
                )
                .build()
                .start()
                .waitFor();

        System.out.println("Wire measurement (granted) session finished.");
    }
}
