package net.thevpc.naru.impl.util;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class StoredStringMap<T> {
    private final NPath file;
    private Function<T, NElement> serializer;
    private Function<NElement, T> deserializer;
    private final Map<String, T> map = new HashMap<>();
    private final Class<T> type;

    public StoredStringMap(NPath file, Class<T> type) {
        this.file = file;
        this.type = type;
    }

    public Function<T, NElement> getSerializer() {
        return serializer;
    }

    public StoredStringMap<T> setSerializer(Function<T, NElement> serializer) {
        this.serializer = serializer;
        return this;
    }

    public Function<NElement, T> getDeserializer() {
        return deserializer;
    }

    public StoredStringMap<T> setDeserializer(Function<NElement, T> deserializer) {
        this.deserializer = deserializer;
        return this;
    }

    public NOptional<T> get(String k) {
        load();
        return NOptional.of(map.get(k));
    }

    public StoredStringMap<T> put(String k, T v) {
        load();
        map.put(k, v);
        save();
        return this;
    }

    /**
     * Rewrite the file in place, keeping what the reader put on the tree.
     *
     * <p>TSON is round-trip aware: a parse keeps comments, blank lines and spacing as affixes on
     * the elements they belong to, and writing the parsed tree back with the VERBATIM formatter
     * reproduces the file. So an edit is applied to that tree rather than to a map that gets
     * re-emitted from scratch, and a hand-written {@code env.tson} — with the comment that says why
     * a cap is 40000 — survives the settings panel changing one value in it.
     *
     * <p>Three limits, all measured against a parsed file rather than assumed:
     * <ul>
     *   <li>a key that is new goes at the END of the object, never between two existing pairs:
     *       inserting mid-object steals the last word of the comment above it;
     *   <li>an end-of-line comment on the pair the edit lands on is lost with that pair's own
     *       formatting, because the replacement value carries no affixes;
     *   <li>a file that opens with a comment writes back with the opening brace on the comment's own
     *       line: that newline is an affix inside the comment, and re-adding it at the object's START
     *       anchor lands between the comment's words rather than after them;
     *   <li>a file that does not parse as an object is rewritten from the map, the same way this
     *       store always has. Corrupt input gets a readable file, not a crash.
     * </ul>
     */
    public synchronized void save() {
        NElement root = readTreeForRewrite();
        if (root == null) {
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY).write(map, file);
            return;
        }
        NObjectElement object = objectOf(root);
        if (object == null) {
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY).write(map, file);
            return;
        }
        NObjectElementBuilder b = object.builder();
        Map<String, NElement> onDisk = new LinkedHashMap<>();
        for (NPairElement p : object.namedPairs()) {
            String k = p.key().asStringValue().orNull();
            if (k != null) {
                onDisk.put(k, p.value());
            }
        }
        List<String> sorted = new ArrayList<>(map.keySet());
        sorted.sort(Comparator.naturalOrder());
        for (String k : sorted) {
            NElement wanted = serializeValue(map.get(k));
            NElement had = onDisk.get(k);
            if (had == null) {
                NElement pair = NElement.ofPair(k, wanted);
                // the newline is what separates the appended pair from the one before it: TSON
                // tolerates a missing comma but not two pairs glued into one token
                pair = pair.builder().addAffix(NElementNewLine.of(), NAffixAnchor.START)
                        .addAffix(NElementSpace.of(2), NAffixAnchor.START).build();
                b.setAt(b.size(), pair);
            } else if (!had.toString().equals(wanted.toString())) {
                b.set(k, wanted);
            }
        }
        for (String k : onDisk.keySet()) {
            if (!map.containsKey(k)) {
                b.remove(k);
            }
        }
        NElement out = root instanceof NFragmentElement fragment
                ? fragment.builder().setAt(indexOfObject(fragment), b.build()).build()
                : b.build();
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.VERBATIM).write(out, file);
    }

    /** The parsed file, or null when there is nothing to edit in place. */
    private NElement readTreeForRewrite() {
        if (!file.isRegularFile()) {
            return null;
        }
        try {
            return NElementReader.ofTson().ntf(false).read(file);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static NObjectElement objectOf(NElement root) {
        if (root instanceof NFragmentElement fragment) {
            for (NElement c : fragment) {
                if (c.asObject().isPresent()) {
                    return c.asObject().get();
                }
            }
            return null;
        }
        return root.asObject().orNull();
    }

    private static int indexOfObject(NFragmentElement fragment) {
        int i = 0;
        for (NElement c : fragment) {
            if (c.asObject().isPresent()) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private T deserializeValue(NElement value) {
        if (deserializer != null) {
            T a = deserializer.apply(value);
            return a;
        }
        return NElement.convertAny(value, type);
    }

    private NElement serializeValue(T value) {
        if (serializer != null) {
            NElement a = serializer.apply(value);
            if (a == null) {
                return NElement.ofNull();
            }
            return a;
        }
        if (value == null) {
            return NElement.ofNull();
        }
        return NElement.of(value);
    }


    public synchronized StoredStringMap<T> load() {
        map.clear();
        if (file.isRegularFile()) {
            // a file that opens with a comment parses as a FRAGMENT wrapping the object, not as an
            // object: reading only the bare case silently drops every key a team documented
            NElement a = NElementReader.ofTson().read(file);
            NObjectElement object = objectOf(a);
            if (object != null) {
                for (NPairElement p : object.namedPairs()) {
                    String k = p.key().asStringValue().orNull();
                    NElement v = p.value();
                    if (k != null && v != null) {
                        map.put(k, deserializeValue(v));
                    }
                }
            }
        }
        return this;
    }

    public Map<String, T> toMap() {
        load();
        return new HashMap<>(map);
    }

    public void remove(String alias) {
        load();
        map.remove(alias);
        save();
    }
}
