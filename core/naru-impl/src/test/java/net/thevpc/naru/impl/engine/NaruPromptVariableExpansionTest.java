package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.model.NaruModelContext;
import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A prompt line expands the variables it names, so a routine's arguments reach the model.
 *
 * <p>Before this, only directives expanded {@code $name}: a routine called with
 * {@code ticket="..."} could print the ticket but sent the model the literal text
 * {@code Classify $ticket}.
 */
public class NaruPromptVariableExpansionTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    @Timeout(60)
    public void aPromptLineSendsTheValuesOfTheVariablesItNames() {
        List<String> seen = run(NaruTaskSpec.of()
                .var("customer", NElement.ofString("Ada"))
                .statements(
                        "/use classify",
                        "10 Classify ${ticket} for $customer",
                        "/routine main",
                        "/call classify ticket=\"my parcel is late\"",
                        "Summarize: $_",
                        "Run echo $HOME and ${PATH} in the shell",
                        "Write $$customer and $${customer} literally"
                ));

        assertEquals(List.of(
                "Classify my parcel is late for Ada",
                "Summarize: answer 1",
                "Run echo $HOME and ${PATH} in the shell",
                "Write $customer and ${customer} literally"
        ), seen);
    }

    private static List<String> run(NaruTaskSpec spec) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-prompt-vars-" + System.nanoTime());
        dir.mkdirs();
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .sessionDir(dir)
                // a named session is never auto-titled, so the titler makes no model calls here
                .name("prompt-vars")
                .interaction(new Silent())
                .build();
        Recorder recorder = new Recorder();
        s.registry().registerModelProvider(recorder);
        NaruTask task = s.newTask(spec);
        recorder.taskId = task.id();
        task.setModel(new NaruModelConfig(Recorder.NAME, "m"));
        s.start();
        task.unhold();
        assertTrue(task.await(Duration.ofSeconds(120)), () -> "the script did not finish: status="
                + task.status() + ", prompts=" + recorder.prompts + ", history tail="
                + task.history().stream().skip(Math.max(0, task.history().size() - 6))
                .map(m -> m.getRole() + ":" + m.getFilteredContent()).toList());
        return recorder.prompts;
    }

    /** Records the last user message of every request, and answers "answer N". */
    private static final class Recorder implements NaruModelProvider {
        static final String NAME = "prompt-vars-fake";
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        /** The script's task: other callers (the session titler) share the model and are ignored. */
        volatile long taskId = -1;

        private final NaruModelProtocol protocol = new NaruModelProtocol() {
            @Override
            public NaruResponse chat(NaruModelRequest request, NaruModelContext context) {
                if (context == null || context.taskId() != taskId) {
                    return new NaruResponse(NaruMessage.assistant("title"), true);
                }
                List<NaruMessage> messages = request.messages();
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i).getRole() == NaruRole.user) {
                        prompts.add(messages.get(i).getFilteredContent());
                        break;
                    }
                }
                return new NaruResponse(NaruMessage.assistant("answer " + prompts.size()), true);
            }

            @Override
            public String providerName() {
                return NAME;
            }

            @Override
            public NaruModelCapabilities getCapabilities() {
                return new NaruModelCapabilities() {
                    @Override
                    public long contextLength() {
                        return 4096;
                    }

                    @Override
                    public boolean isVision() {
                        return false;
                    }

                    @Override
                    public boolean isTools() {
                        return false;
                    }

                    @Override
                    public boolean isThinking() {
                        return false;
                    }

                    @Override
                    public boolean isEmbedding() {
                        return false;
                    }

                    @Override
                    public boolean isTextOnly() {
                        return true;
                    }

                    @Override
                    public boolean isStreaming() {
                        return false;
                    }

                    @Override
                    public NaruCachingMode cachingMode() {
                        return NaruCachingMode.NONE;
                    }

                    @Override
                    public Set<String> keys() {
                        return Set.of();
                    }

                    @Override
                    public NElement toElement() {
                        return NElement.ofTupleBuilder("Capabilities").build();
                    }
                };
            }
        };

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruModelContext context) {
            return NOptional.of(protocol);
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public NOptional<String> apiKey(NaruModelContext context) {
            return NOptional.ofEmpty();
        }

        @Override
        public List<String> findModelIds(NaruModelContext context) {
            return List.of("m");
        }

        @Override
        public void setParam(String name, String value) {
        }

        @Override
        public NOptional<String> getParam(String name) {
            return NOptional.ofEmpty();
        }

        @Override
        public Set<String> getParamNames() {
            return Set.of();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }

    private static final class Silent implements NaruInteraction {
        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        }

        @Override
        public void close() {
        }
    }
}
