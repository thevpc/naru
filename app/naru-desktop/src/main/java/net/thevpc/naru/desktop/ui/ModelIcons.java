package net.thevpc.naru.desktop.ui;

import java.util.Locale;

/**
 * A mark for a model provider, for the picker rows and the button that opens it.
 *
 * <p>The engine describes a model by key — {@code ollama/qwen2.5-coder:7b} — which is precise and
 * unreadable at a glance in a list of two hundred of them. A provider glyph gives the eye something
 * to sort by before it reads. It carries no colour: hue in this application means state, and a
 * provider is not a state.
 */
public final class ModelIcons {

    /**
     * Providers whose name is long enough, or whose mark is well known enough, to be worth a glyph.
     *
     * <p>Every mark here is a plain geometric or dingbat code point: an emoji (a llama for Ollama, a
     * whale for DeepSeek) is a colour picture from a font this application does not have, and on
     * those machines it paints as nothing at all, which leaves a button whose mark silently vanished.
     */
    private static java.util.Map<String, String> named() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("ollama", "◍");
        m.put("llamacpp", "◈");
        m.put("llama.cpp", "◈");
        m.put("openai", "❋");
        m.put("anthropic", "✳");
        m.put("gemini", "✦");
        m.put("google", "✦");
        m.put("deepseek", "◇");
        m.put("mistral", "❂");
        m.put("grok", "✴");
        m.put("cohere", "❈");
        m.put("azure", "▲");
        m.put("localai", "▣");
        m.put("localgpt", "▣");
        m.put("lmstudio", "▣");
        m.put("nvidia", "▦");
        m.put("voyage", "◆");
        m.put("sagemaker", "▲");
        m.put("bedrock", "▲");
        m.put("groq", "⚡");
        return m;
    }

    private static java.util.Map<String, String> glyphs;

    private ModelIcons() {
    }

    /**
     * The mark for {@code provider}: its known glyph, or its first letter when the provider is one
     * this build has no mark for. Never empty — a row without a leading mark would sit out of line
     * with the rows that have one.
     */
    public static String glyph(String provider) {
        if (glyphs == null) {
            glyphs = named();
        }
        if (provider == null || provider.isBlank()) {
            return "●";
        }
        String key = provider.toLowerCase(Locale.ROOT);
        String known = glyphs.get(key);
        if (known == null) {
            for (java.util.Map.Entry<String, String> e : glyphs.entrySet()) {
                if (key.contains(e.getKey()) || e.getKey().contains(key)) {
                    known = e.getValue();
                    break;
                }
            }
        }
        return known != null ? known : Character.toString(Character.toUpperCase(key.charAt(0)));
    }

    /**
     * The button's text: a mark and a model name, no provider. The key belongs in the tooltip — at
     * the largest interface size the row also holds the mode, the thinking box and Send, and a
     * {@code provider/model:tag} does not fit beside them.
     */
    public static String buttonLabel(String provider, String model, int maxNameChars) {
        String mark = glyph(provider);
        String name = model == null || model.isBlank() ? "no model" : model;
        if (name.length() > maxNameChars) {
            name = name.substring(0, Math.max(4, maxNameChars - 1)) + "…";
        }
        return mark + " " + name;
    }
}
