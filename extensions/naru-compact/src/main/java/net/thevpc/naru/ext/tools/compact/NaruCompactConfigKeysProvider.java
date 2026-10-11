package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The {@code naru.compact.*} settings — the vocabulary of {@code /compact}.
 *
 * <p>Every key is resolved per task through {@link NaruTaskConfig} (see
 * {@link NaruCompactConfig} for the defaults), so a value set with
 * {@code /set naru.compact.level = aggressive} in a script takes effect exactly
 * as a project config file would. Declared here, next to the reads, so the
 * settings surface offers the same keys {@code /compact status} reports.
 */
public class NaruCompactConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of(NaruCompactConfig.AUTO,
                        "auto-compact the conversation when it crosses the threshold")
                        .withValues("true", "false"),
                NaruConfigKey.of(NaruCompactConfig.THRESHOLD,
                        "context fraction at which auto-compaction fires (0.0-1.0, default 0.80)"),
                NaruConfigKey.of(NaruCompactConfig.TARGET,
                        "context fraction compaction aims to land at (0.0-1.0, default 0.40)"),
                NaruConfigKey.of(NaruCompactConfig.LEVEL,
                        "summarization effort preset").withValues("light", "normal", "aggressive"),
                NaruConfigKey.of(NaruCompactConfig.KEEP,
                        "keep window of the recent history, e.g. 4turns"),
                NaruConfigKey.of(NaruCompactConfig.MODELS,
                        "ordered model keys to try for summarization (comma separated)"),
                NaruConfigKey.of(NaruCompactConfig.MODELS_INCLUDE_CURRENT,
                        "append the task's current model as a last-resort summarizer")
                        .withValues("true", "false"),
                NaruConfigKey.of(NaruCompactConfig.MAX_TOKENS,
                        "explicit token target of a compaction; unset derives it from target"),
                NaruConfigKey.of(NaruCompactConfig.MIN_GAP,
                        "items that must be added before auto-compaction may fire again (anti-loop)"),
                NaruConfigKey.of(NaruCompactConfig.ON_STALE,
                        "what a summary whose covered content changed does")
                        .withValues("deactivate", "keep"),
                NaruConfigKey.of(NaruCompactConfig.MODEL_CAN_COMPACT,
                        "let the agent call the compaction tool itself")
                        .withValues("true", "false"),
                NaruConfigKey.of(NaruCompactConfig.CACHE_ENABLED,
                        "cache summarizer results").withValues("true", "false"),
                NaruConfigKey.of(NaruCompactConfig.CACHE_MAX_ENTRIES,
                        "maximum summarizer cache entries")
        );
    }
}