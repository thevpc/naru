package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruConfigKey;
import net.thevpc.naru.api.registry.NaruConfigKeyProvider;

import java.util.List;

/**
 * The {@code naru.fs.*} caps the file tools read through {@code NaruTaskConfig}
 * (see {@link FileToolHelper}): every preview and listing limit a project can
 * raise or lower per task, session, agent or config file without touching code.
 * Declared here, next to the reads, so the settings surface offers exactly the
 * keys the tools understand.
 */
public class NaruFsConfigKeysProvider implements NaruConfigKeyProvider {

    @Override
    public String name() {
        return "fs";
    }

    @Override
    public List<NaruConfigKey> configKeys(NaruSession session) {
        return List.of(
                NaruConfigKey.of("naru.fs.read.previewChars",
                        "characters of a preview the file tools keep before truncating"),
                NaruConfigKey.of("naru.fs.read.maxLines",
                        "maximum lines a file read returns"),
                NaruConfigKey.of("naru.fs.read.lineNumbers",
                        "prefix every file-read line with its number").withValues("true", "false"),
                NaruConfigKey.of("naru.fs.find.contextLines",
                        "context lines printed around each name match"),
                NaruConfigKey.of("naru.fs.find.maxMatches",
                        "maximum matches a file/folder find reports"),
                NaruConfigKey.of("naru.fs.find.maxFiles",
                        "maximum files a file/folder find walks"),
                NaruConfigKey.of("naru.fs.grep.maxTotalMatches",
                        "maximum total matches grep reports"),
                NaruConfigKey.of("naru.fs.grep.maxOutputChars",
                        "characters of grep output kept before truncating")
        );
    }
}