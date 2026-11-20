package bench;

import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;

import java.nio.file.Files;
import java.nio.file.Path;

public class NaruWireTest {
    public static void main(String[] args) throws Exception {
        Nuts.require();

        Path tempDir = Files.createTempDirectory("naru-wire");
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(NPath.of(tempDir.toString()));

        agent.newSession()
                .statements(
                        "/model add bench-ollama --protocol=openai --url=http://127.0.0.1:8088/naru-wire-test/v1 --models=qwen3.8:32k",
                        "/model use bench-ollama/qwen3.8:32k",
                        "/tags enable fs write exec network plan",
                        "Reply with single word OK"
                )
                .build()
                .start()
                .waitFor();

        System.out.println("NARU Wire test finished!");
    }
}
