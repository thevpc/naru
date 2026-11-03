package net.thevpc.naru.impl.util;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NPairElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The project config files are hand-written and checked in, so an edit through
 * {@code /settings} or the settings panel must not cost the user their comments.
 * <p>
 * These assertions are about the text on disk, not the in-memory map: a store that round-trips
 * the map correctly and flattens the file is exactly the failure this file exists to catch.
 */
public class StoredStringMapRoundtripTest {

    private static final String SOURCE = ""
            + "# team defaults for this project\n"
            + "{\n"
            + "  # the model a fresh session starts on\n"
            + "  model : \"ollama/qwen2.5-coder:7b\",\n"
            + "  \"naru.fs.grep.maxOutputChars\": 15000,\n"
            + "  \"naru.fs.read.previewChars\": 40000\n"
            + "}\n";

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    private static NPath fileWith(String text) {
        NPath folder = NPath.ofTempFolder("naru-stored-map-" + System.nanoTime()).resolve("config");
        folder.mkdirs();
        NPath f = folder.resolve("env.tson");
        f.writeString(text);
        return f;
    }

    private static List<String> keysOf(String text) {
        NElement root = NElementReader.ofTson().read(text);
        NObjectElement o = root.asObject().orNull();
        if (o == null) {
            for (NElement c : (net.thevpc.nuts.elem.NFragmentElement) root) {
                o = c.asObject().orNull();
                if (o != null) {
                    break;
                }
            }
        }
        Assertions.assertNotNull(o, "the rewritten file no longer parses as an object:\n" + text);
        List<String> out = new ArrayList<>();
        for (NPairElement p : o.namedPairs()) {
            out.add(p.key().asStringValue().orNull() + "=" + p.value());
        }
        return out;
    }

    @Test
    public void changingAValueKeepsTheCommentsAndTheOrder() {
        NPath f = fileWith(SOURCE);
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.put("model", NElement.of("ollama/other:9"));

        String text = f.readString();
        Assertions.assertTrue(text.contains("# team defaults for this project"),
                "the file's leading comment is the team's, not ours to drop:\n" + text);
        Assertions.assertTrue(text.contains("# the model a fresh session starts on"),
                "the comment above the edited key describes the value we just changed:\n" + text);
        Assertions.assertEquals(
                List.of("model=\"ollama/other:9\"",
                        "naru.fs.grep.maxOutputChars=15000",
                        "naru.fs.read.previewChars=40000"),
                keysOf(text), "the edit landed on the key it names, in the order the file has");
    }

    @Test
    public void aBrandNewKeyIsAppendedWithoutDisturbingTheOthers() {
        NPath f = fileWith(SOURCE);
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.put("naru.fs.find.maxFiles", NElement.of("20"));

        String text = f.readString();
        List<String> keys = keysOf(text);
        Assertions.assertEquals("naru.fs.find.maxFiles=\"20\"", keys.get(keys.size() - 1),
                "a new key goes at the end: inserted mid-object it takes the last word of the"
                        + " comment above it with it:\n" + text);
        Assertions.assertTrue(text.contains("# the model a fresh session starts on"),
                "and the comment above the first key is still whole:\n" + text);
        Assertions.assertTrue(keys.contains("model=\"ollama/qwen2.5-coder:7b\""),
                "the untouched keys kept both their value and their place: " + keys);
    }

    @Test
    public void removingAKeyLeavesTheRestOfTheFileAlone() {
        NPath f = fileWith(SOURCE);
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.remove("naru.fs.grep.maxOutputChars");

        String text = f.readString();
        Assertions.assertEquals(
                List.of("model=\"ollama/qwen2.5-coder:7b\"", "naru.fs.read.previewChars=40000"),
                keysOf(text));
        Assertions.assertTrue(text.contains("# team defaults for this project"),
                "a removal is not a rewrite of everything else:\n" + text);
    }

    @Test
    public void aFileThatDoesNotParseIsRewrittenRatherThanRefused() {
        NPath f = fileWith("{ this is not tson ]]]");
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.put("model", NElement.of("ollama/other:9"));

        String text = f.readString();
        Assertions.assertEquals(List.of("model=\"ollama/other:9\""), keysOf(text),
                "corrupt input gets a readable file, not a store that can no longer be saved to");
    }

    @Test
    public void theFirstWriteCreatesTheFileFromTheMap() {
        NPath f = NPath.ofTempFolder("naru-stored-map-new-" + System.nanoTime())
                .resolve("config").resolve("env.tson");
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.put("model", NElement.of("ollama/first:1"));

        Assertions.assertTrue(f.isRegularFile(), "a store with no file behind it still has to persist");
        Assertions.assertEquals(List.of("model=\"ollama/first:1\""),
                keysOf(f.readString()));
    }

    /**
     * A registration value is a nested object, and a nested object off a builder carries no
     * separators between its pairs at all — written verbatim, the file re-parses with one value
     * swallowing the next key's name, and the store reads back a registration that lost its
     * {@code model}. Both shapes are what a settings save does: replace a value, and append one.
     */
    @Test
    public void rewritingANestedValueKeepsEveryFieldOfIt() {
        NPath f = fileWith("{\n"
                + "  \"shared\": { provider : \"stub\", model : \"s-model\", temperature : 0.3 }\n"
                + "}\n");
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        // the same object, its fields in a different order — what a second save of the same
        // registration from a Map.of(...) looks like
        map.put("shared", NElement.ofObjectBuilder()
                .set("temperature", NElement.of(0.3f))
                .set("model", NElement.ofString("s-model"))
                .set("url", NElement.ofString("https://personal.test/v1"))
                .set("provider", NElement.ofString("stub"))
                .build());

        String text = f.readString();
        NElement parsed = NElementReader.ofTson().read(text);
        NObjectElement shared = valueOf(parsed, "shared");
        Assertions.assertNotNull(shared, "the file no longer holds an object under 'shared':\n" + text);
        Assertions.assertEquals(List.of("temperature", "model", "url", "provider"),
                namesOf(shared), "every field of the rewritten value is still its own pair:\n" + text);
    }

    @Test
    public void appendingANestedValueIsReadableOnTheNextLoad() {
        NPath f = fileWith(SOURCE);
        StoredStringMap<NElement> map = new StoredStringMap<>(f, NElement.class);
        map.put("shared", NElement.ofObjectBuilder()
                .set("provider", NElement.ofString("stub"))
                .set("model", NElement.ofString("s-model"))
                .build());

        NObjectElement shared = valueOf(NElementReader.ofTson().read(f.readString()), "shared");
        Assertions.assertNotNull(shared, "the appended value is not an object anymore:\n"
                + f.readString());
        Assertions.assertEquals(List.of("provider", "model"), namesOf(shared));
    }

    private static NObjectElement valueOf(NElement root, String key) {
        List<NElement> candidates = new ArrayList<>();
        if (root instanceof net.thevpc.nuts.elem.NFragmentElement fragment) {
            for (NElement c : fragment) {
                candidates.add(c);
            }
        } else {
            candidates.add(root);
        }
        for (NElement candidate : candidates) {
            NObjectElement o = candidate.asObject().orNull();
            if (o == null) {
                continue;
            }
            for (NPairElement p : o.namedPairs()) {
                if (key.equals(p.key().asStringValue().orNull())) {
                    return p.value().asObject().orNull();
                }
            }
        }
        return null;
    }

    private static List<String> namesOf(NObjectElement o) {
        List<String> out = new ArrayList<>();
        for (NPairElement p : o.namedPairs()) {
            out.add(p.key().asStringValue().orNull());
        }
        return out;
    }
}
