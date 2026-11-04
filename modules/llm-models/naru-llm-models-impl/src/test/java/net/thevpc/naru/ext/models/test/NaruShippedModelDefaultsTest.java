package net.thevpc.naru.ext.models.test;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.util.NBlankable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-model defaults this extension ships under {@code META-INF/naru/models}.
 *
 * <p>NARU resolves those files for the selected model and merges their front
 * matter into the request env, so a shipped default is the emulated format the
 * model is instructed (by the same file's body) to emit. The two halves must
 * agree: an unwrapper told to close on one tag while the model is told to close
 * on another extracts nothing, which is exactly the self-contradiction that
 * used to sit in the local qwen2.5-coder example. This test pins the files that
 * make emulation work out of the box.
 */
public class NaruShippedModelDefaultsTest {

    private static final List<String> SHIPPED =
            List.of(
                    "META-INF/naru/models/qwen2.5-coder.md",
                    "META-INF/naru/models/apodex/apodex-1.1-mini@free.md");

    @BeforeAll
    public static void setUp() {
        Nuts.require();
    }

    @Test
    public void shippedDefaultsAreReachableAndSelfConsistent() throws Exception {
        for (String resourcePath : SHIPPED) {
            String text = readResource(resourcePath);
            Map<String, String> header = parseFrontMatter(text);
            String body = bodyOf(text);

            Assertions.assertFalse(header.isEmpty(),
                    resourcePath + " must carry a front matter");
            Assertions.assertEquals("true", header.get("emulate_tool_calls"),
                    resourcePath + " must be an emulated-call model by default");

            String start = header.get("tool_call_start");
            String end = header.get("tool_call_end");
            Assertions.assertFalse(NBlankable.isBlank(start) || NBlankable.isBlank(end),
                    resourcePath + " must configure both ends of the call-tag pair");

            String resultStart = header.get("tool_result_start");
            String resultEnd = header.get("tool_result_end");
            Assertions.assertFalse(NBlankable.isBlank(resultStart) || NBlankable.isBlank(resultEnd),
                    resourcePath + " must configure both ends of the result-tag pair");

            // the body is what the model is told to write; the unwrapper reads the
            // same tags, so the body must actually spell them
            Assertions.assertTrue(body.contains(start),
                    resourcePath + ": the body must open blocks with tool_call_start");
            Assertions.assertTrue(body.contains(end),
                    resourcePath + ": the body must close blocks with tool_call_end (" + end + ")");
        }
    }

    private static String readResource(String resourcePath) throws Exception {
        try (InputStream in = NaruShippedModelDefaultsTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            Assertions.assertNotNull(in,
                    resourcePath + " must be packaged as a classpath resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseFrontMatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!text.startsWith("---")) {
            return out;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        for (String line : text.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(line.substring(0, colon).trim(), value);
        }
        return out;
    }

    private static String bodyOf(String text) {
        int end = text.indexOf("\n---", 3);
        return end < 0 ? text : text.substring(end + 4).trim();
    }
}