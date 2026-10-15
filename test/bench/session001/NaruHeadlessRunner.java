package bench;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class NaruHeadlessRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: NaruHeadlessRunner <project-dir> <proxy-url> <model> <config: min|granted> <prompt>");
            System.exit(1);
        }

        String projectDir = args[0];
        String proxyUrl = args[1];
        String model = args[2];
        String config = args[3];
        String prompt = args[4];

        Nuts.require();

        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.of(projectDir));

        List<String> stmts = new ArrayList<>();
        stmts.add(String.format("/model add bench-model --protocol=openai --url=%s --models=%s", proxyUrl, model));
        stmts.add("/model use bench-model/" + model);
        stmts.add("/config naru.compact.auto=false");
        stmts.add("/config naru.fs.read.lineNumbers=true");
        stmts.add("/tags enable fs write exec network");

        if ("min".equalsIgnoreCase(config)) {
            stmts.add("/tools exclude git_commit ollama_status ollama_start ollama_stop ollama_install ollama_ps routine_run routine_add_line maven_compile maven_test maven_package web_search");
        }

        stmts.add(prompt);

        System.out.println("Starting NARU headless session with model bench-model/" + model + "...");
        agent.newSession()
                .statements(stmts.toArray(new String[0]))
                .build()
                .start()
                .waitFor();
        System.out.println("NARU session finished.");
    }
}
