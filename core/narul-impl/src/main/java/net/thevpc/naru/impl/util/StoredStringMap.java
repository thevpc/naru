package net.thevpc.naru.impl.util;

import net.thevpc.nuts.elem.*;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.HashMap;
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
        load();
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
        return NOptional.ofNullable(map.get(k));
    }

    public StoredStringMap<T> put(String k, T v) {
        load();
        map.put(k, v);
        save();
        return this;
    }

    public synchronized void save() {
        NObjectElementBuilder m2 = NObjectElementBuilder.of();
        for (Map.Entry<String, T> e : map.entrySet()) {
            m2.set(e.getKey(), serializeValue(e.getValue()));
        }
        NElementWriter.ofTson().setNtf(false).setFormatter(NElementFormatterStyle.PRETTY).write(map, file);
    }

    private T deserializeValue(NElement value) {
        if (deserializer != null) {
            T a = deserializer.apply(value);
            return a;
        }
        return NElements.of().fromElement(value, type);
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
        return NElements.of().toElement(value);
    }


    public synchronized StoredStringMap<T> load() {
        map.clear();
        if (file.isRegularFile()) {
            NElement a = NElementReader.ofTson().read(file);
            if (a instanceof NObjectElement) {
                for (NPairElement p : ((NObjectElement) a).namedPairs()) {
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
