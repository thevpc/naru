package net.thevpc.naru.ext.skills;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keyword and lightweight semantic search over skills, shared by {@code /skill list} /
 * {@code /skill available} and the {@code skill} tool.
 * <p>
 * The keyword mode is a plain case-insensitive match over the skill name and description
 * (the whole query as a substring, or every query token present as a token). The semantic
 * mode is a self-contained TF-IDF cosine ranking over name, description and body — not a
 * neural embedding, but enough to order skills by relevance and to match on tokens a
 * substring test would miss (e.g. word order, interface vs implementation). It deliberately
 * lives in this extension so the feature ships and disappears with {@code naru-skills}; no
 * shared embedding SPI is required.
 */
final class NaruSkillSearch {

    private NaruSkillSearch() {
    }

    /** One ranked skill. The score is {@code 1.0} for a keyword match, the cosine in semantic mode, and 0 with no query. */
    static final class Hit {
        private final NaruSkill skill;
        private final double score;

        Hit(NaruSkill skill, double score) {
            this.skill = skill;
            this.score = score;
        }

        NaruSkill skill() {
            return skill;
        }

        double score() {
            return score;
        }
    }

    /**
     * Filters (keyword/semantic) and/or ranks the given skills against {@code query}.
     * A blank query returns every skill in name order with a 0 score. Keyword mode keeps
     * matches in name order; semantic mode keeps only skills with a positive cosine and
     * orders them by descending relevance.
     */
    static List<Hit> search(List<NaruSkill> skills, String query, boolean semantic) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            List<Hit> all = new ArrayList<>();
            for (NaruSkill s : skills) {
                all.add(new Hit(s, 0));
            }
            all.sort(Comparator.comparing(h -> h.skill().getName()));
            return all;
        }
        if (!semantic) {
            List<Hit> hits = new ArrayList<>();
            for (NaruSkill s : skills) {
                if (keywordMatches(s, q)) {
                    hits.add(new Hit(s, 1.0));
                }
            }
            hits.sort(Comparator.comparing(h -> h.skill().getName()));
            return hits;
        }
        return semanticRank(skills, q);
    }

    /** Case-insensitive substring match over name+description, or every query token present. */
    static boolean keywordMatches(NaruSkill skill, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String hay = (skill.getName() + " " + nullToEmpty(skill.getDescription())).toLowerCase(Locale.ROOT);
        if (hay.contains(needle)) {
            return true;
        }
        List<String> tokens = tokenize(needle);
        if (tokens.isEmpty()) {
            return false;
        }
        Set<String> hayTokens = new HashSet<>(tokenize(hay));
        for (String t : tokens) {
            if (!hayTokens.contains(t)) {
                return false;
            }
        }
        return true;
    }

    /** The origin family a skill came from: {@code naru} for native roots, else {@code claude}/{@code agents}/... */
    static String sourceLabel(NaruSkill skill) {
        NaruSkillRoot root = skill.getRoot();
        if (root == null) {
            return "naru";
        }
        String label = root.label();
        if (label == null || label.isBlank()) {
            return root.kind().foreign() ? "foreign" : "naru";
        }
        return label;
    }

    // ── semantic (TF-IDF cosine) ────────────────────────────────────────────

    private static List<Hit> semanticRank(List<NaruSkill> skills, String query) {
        int n = skills.size();
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Integer>> docs = new ArrayList<>(n);
        for (NaruSkill s : skills) {
            Map<String, Integer> tf = new LinkedHashMap<>();
            for (String t : tokenize(document(s))) {
                tf.merge(t, 1, Integer::sum);
            }
            docs.add(tf);
            for (String t : tf.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        Map<String, Integer> queryTf = new LinkedHashMap<>();
        for (String t : tokenize(query)) {
            queryTf.merge(t, 1, Integer::sum);
        }
        Map<String, Double> queryVec = vector(queryTf, df, n);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double score = cosine(queryVec, vector(docs.get(i), df, n));
            if (score > 0) {
                hits.add(new Hit(skills.get(i), score));
            }
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> h.score()).reversed()
                .thenComparing(h -> h.skill().getName()));
        return hits;
    }

    private static String document(NaruSkill skill) {
        String body = skill.getFormattedText();
        return skill.getName() + " " + nullToEmpty(skill.getDescription()) + " " + (body == null ? "" : body);
    }

    /**
     * A unit-length TF-IDF vector for one term-frequency map. IDF comes from the corpus
     * ({@code ln((n+1)/(df+1)) + 1}); a term that no document has still contributes to the
     * query's norm, which only shrinks unrelated scores and is harmless.
     */
    private static Map<String, Double> vector(Map<String, Integer> tf, Map<String, Integer> df, int n) {
        Map<String, Double> v = new LinkedHashMap<>();
        double norm = 0;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double idf = Math.log((n + 1.0) / (df.getOrDefault(e.getKey(), 0) + 1.0)) + 1.0;
            double w = e.getValue() * idf;
            v.put(e.getKey(), w);
            norm += w * w;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (Map.Entry<String, Double> e : v.entrySet()) {
                e.setValue(e.getValue() / norm);
            }
        }
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> big = small == a ? b : a;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double other = big.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        return dot;
    }

    /**
     * Lowercase word tokens, split on any non-alphanumeric character and at camelCase
     * boundaries, so {@code gitFlow}, {@code git-flow} and {@code git flow} all yield
     * {@code git}, {@code flow}.
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (cur.length() > 0) {
                    char prev = cur.charAt(cur.length() - 1);
                    if (Character.isUpperCase(c) && Character.isLowerCase(prev)) {
                        out.add(cur.toString().toLowerCase(Locale.ROOT));
                        cur.setLength(0);
                    }
                }
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString().toLowerCase(Locale.ROOT));
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
