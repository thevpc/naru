package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionTitler;
import net.thevpc.naru.api.agent.NaruTitleException;
import net.thevpc.naru.api.agent.NaruTitleException.Attempt;
import net.thevpc.naru.api.agent.NaruTitleMode;
import net.thevpc.naru.api.agent.NaruTitleRequest;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruThinkingConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instruction a title is asked for with.
 * <p>
 * One constant rather than prose at each call site, because the wording is the part that
 * has to be tuned against real models and a rule that lives in two places gets tuned in
 * one of them.
 * <p>
 * The constraints are ordered by how often a model breaks them. "Reply with the title only"
 * comes last and is repeated in different words because preamble -- "Sure, here is a title:"
 * -- is the most common failure and the sanitizer's job is to survive it, not to invite it.
 * "At most six words" bounds the answer before the cap does; a title cut at eighty
 * characters is a sentence, and a title of six words is a name.
 */
final class NaruTitlePrompt {

    static final String INSTRUCTION = """
            You write short titles for conversations.

            Read the conversation excerpt and reply with one title for it.
            The title describes the topic, not the act of asking.
            Use at most six words.
            Write it in the same language the user wrote in.
            One line only, with no quotes, no trailing punctuation, no markdown, \
            no heading, no prefix, and no explanation.
            Reply with the title only. Nothing else.
            """;

    /**
     * The excerpt as one user turn. Labelled lines, because a title written from an
     * unlabelled transcript tends to describe whoever talked most.
     */
    static String userContent(List<String> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation excerpt:\n");
        for (String t : turns) {
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /** The single-message form used when the session has only a first message to go on. */
    static String firstMessageContent(String firstMessage) {
        return "The user's first message:\n" + firstMessage;
    }

    private NaruTitlePrompt() {
    }
}
