package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAccessTrace;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /permission} — read and edit the session's access policy.
 *
 * <p>This is a human directive, not a model tool: changing what an agent may do
 * should not be something the agent can do to itself. The model-facing side of
 * this feature is deliberately narrower — listing and checking ({@code
 * permission_list}, {@code permission_check}) — while granting and revoking live
 * here and, for a model that holds the {@code perm} tag, in the two mutators that
 * are gated behind it.
 */
public class NaruPermissionsDirectiveProvider extends NaruDirectiveProviderBase {

    static final String USAGE_ADD =
            "usage: /permission add <id> [--type=file|dir|tool|directive|url|shell|model|*] "
                    + "[--pattern=g[,g...]] [--actions=r,w,x] [--subjects=user:a,group:b,agent,*] "
                    + "[--kind=user|agent|system] [--effect=allow|deny] [--index=N] [--desc=...]";
    static final String USAGE_MOVE =
            "usage: /permission move <id> --index=N | --before=id | --after=id";

    public NaruPermissionsDirectiveProvider() {
        super("permissions");
        this.registerDirective(new NaruPermissionDirective());
    }

    public static class NaruPermissionDirective extends NaruDirectiveBase {

        public NaruPermissionDirective() {
            super("permission", "permissions", "list and edit access-control rules");
            noCommand("list");
            register(new AbstractSubCommand("list", NText.ofPlain("list the rules in evaluation order")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    NaruAccessPolicy policy = policy(context);
                    String source = p.one("source");
                    List<NaruPermissionRule> rules = source == null ? policy.rules() : policy.rules(source);
                    if (rules.isEmpty()) {
                        log(context, NMsg.ofP("no rules"
                                + (source == null ? "" : " for source " + source)
                                + " (default " + policy.defaultEffect().name().toLowerCase() + ")"));
                        return NaruStmtResult.ofSuccess(null);
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(rules.size()).append(" rule(s), default ")
                            .append(policy.defaultEffect().name().toLowerCase()).append(":\n");
                    int i = 1;
                    for (NaruPermissionRule r : rules) {
                        sb.append("  ").append(i++).append(". ")
                                .append(NaruPermissionsUtil.describeRule(r)).append('\n');
                    }
                    log(context, NMsg.ofNtf(sb.toString().stripTrailing()));
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("add", NText.ofPlain("add or replace a rule (kept in the session)")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    String id = ruleId(p);
                    if (id == null) {
                        return fail(context, USAGE_ADD);
                    }
                    NaruAccessPolicy policy = policy(context);
                    policy.putRule(buildRule(id, p, null));
                    applyIndex(policy, id, p);
                    log(context, NMsg.ofC("rule added: %s", NaruPermissionsUtil.describeRule(policy.rule(id).get())));
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("update", NText.ofPlain("change fields of an existing rule in place")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    String id = ruleId(p);
                    if (id == null) {
                        return fail(context, "usage: /permission update <id> [--effect=...] [--actions=...] ...");
                    }
                    NaruAccessPolicy policy = policy(context);
                    NaruPermissionRule existing = policy.rule(id).orNull();
                    if (existing == null) {
                        return fail(context, "no such rule '" + id + "'");
                    }
                    policy.putRule(buildRule(id, p, existing));
                    applyIndex(policy, id, p);
                    log(context, NMsg.ofC("rule updated: %s", NaruPermissionsUtil.describeRule(policy.rule(id).get())));
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("remove", NText.ofPlain("remove a rule by id")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    String id = ruleId(p);
                    if (id == null) {
                        return fail(context, "usage: /permission remove <id>");
                    }
                    boolean removed = policy(context).removeRule(id);
                    log(context, NMsg.ofP(removed ? "rule removed: " + id : "no such rule: " + id));
                    return removed
                            ? NaruStmtResult.ofSuccess(null)
                            : NaruStmtResult.ofError("no such rule '" + id + "'");
                }
            });
            register(new AbstractSubCommand("move", NText.ofPlain("change a rule's position (order is precedence)")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    String id = ruleId(p);
                    if (id == null) {
                        return fail(context, USAGE_MOVE);
                    }
                    NaruAccessPolicy policy = policy(context);
                    if (policy.rule(id).isEmpty()) {
                        return fail(context, "no such rule '" + id + "'");
                    }
                    if (p.one("index") == null && p.one("before") == null && p.one("after") == null) {
                        return fail(context, USAGE_MOVE);
                    }
                    applyIndex(policy, id, p);
                    log(context, NMsg.ofC("rule moved: %s", NaruPermissionsUtil.describeRule(policy.rule(id).get())));
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("default", NText.ofPlain("show or change the fallback effect")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    Parsed p = parse(cmdLine);
                    NaruAccessPolicy policy = policy(context);
                    String value = !p.args.isEmpty() ? p.args.get(0)
                            : (p.one("effect") != null ? p.one("effect") : p.one("default"));
                    if (NBlankable.isBlank(value)) {
                        log(context, NMsg.ofC("default effect: %s", policy.defaultEffect().name().toLowerCase()));
                    } else {
                        policy.defaultEffect(NaruPermissionsUtil.parseEffect(value));
                        log(context, NMsg.ofC("default effect set to %s", policy.defaultEffect().name().toLowerCase()));
                    }
                    return NaruStmtResult.ofSuccess(null);
                }
            });
            register(new AbstractSubCommand("check", NText.ofPlain("answer whether an action is allowed")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    return check0(context, parse(cmdLine), false);
                }
            });
            register(new AbstractSubCommand("explain", NText.ofPlain("check and show every rule that was weighed")) {
                @Override
                public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                    return check0(context, parse(cmdLine), true);
                }
            });
        }
    }

    // ── shared parsing and evaluation ─────────────────────────────────────────

    private static NaruAccessPolicy policy(NaruDirectiveCallContext context) {
        return context.task().session().accessPolicy();
    }

    private static void log(NaruDirectiveCallContext context, NMsg msg) {
        context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
    }

    private static NaruStmtResult fail(NaruDirectiveCallContext context, NMsg message) {
//        log(context, NMsg.ofStyledError(message));
//        return NaruStmtResult.ofError(message);
        return NaruStmtResult.ofError(message.asError());
    }

    private static String ruleId(Parsed p) {
        if (!p.args.isEmpty()) {
            return p.args.get(0);
        }
        return p.one("id");
    }

    private static void applyIndex(NaruAccessPolicy policy, String id, Parsed p) {
        if (p.one("index") != null) {
            policy.moveRule(id, Integer.parseInt(p.one("index")));
        } else if (p.one("before") != null) {
            policy.moveRuleBefore(id, p.one("before"));
        } else if (p.one("after") != null) {
            policy.moveRuleAfter(id, p.one("after"));
        }
    }

    /**
     * Builds a rule from the parsed options. When {@code base} is given the
     * fields it does not mention are inherited, so {@code update --effect=deny}
     * flips only the effect and leaves the pattern and subjects alone.
     */
    static NaruPermissionRule buildRule(String id, Parsed p, NaruPermissionRule base) {
        NaruPermissionRule r = base == null ? new NaruPermissionRule() : base;
        r = r.id(id);
        if (p.has("type")) {
            r = r.resourceType(NaruPermissionsUtil.parseResourceType(p.one("type")));
        }
        if (p.has("pattern")) {
            List<String> patterns = new ArrayList<>();
            for (String v : p.all("pattern")) {
                patterns.addAll(NaruPermissionsUtil.splitList(v));
            }
            r = r.patterns(patterns);
        }
        if (p.has("actions") || p.has("action")) {
            r = r.actions(NaruPermissionsUtil.parseActions(
                    p.one("actions") != null ? p.one("actions") : p.one("action")));
        }
        if (p.has("subjects") || p.has("subject")) {
            r = r.subjects(NaruPermissionsUtil.parseSubjects(
                    p.one("subjects") != null ? p.one("subjects") : p.one("subject")));
        }
        if (p.has("kind")) {
            r = r.actorKind(NaruPermissionsUtil.parseActorKind(p.one("kind")));
        }
        if (p.has("effect")) {
            r = r.effect(NaruPermissionsUtil.parseEffect(p.one("effect")));
        }
        if (p.has("desc")) {
            r = r.description(p.one("desc"));
        }
        if (base == null) {
            r = r.source(p.one("source") == null
                    ? NaruPermissionsExtension.SOURCE_SESSION
                    : p.one("source"));
        } else if (p.has("source")) {
            r = r.source(p.one("source"));
        }
        return r;
    }

    private static NaruAccessRequest request(NaruDirectiveCallContext context, Parsed p) {
        String locator = p.one("resource") != null
                ? p.one("resource")
                : (p.args.isEmpty() ? null : p.args.get(0));
        NaruResource resource = NaruPermissionsUtil.resourceOf(p.one("type"), locator);
        NaruAction action = p.one("action") == null ? NaruAction.ANY : NaruAction.parse(p.one("action"));
        NaruActor actor = p.one("actor") == null
                ? context.task().currentActor()
                : NaruPermissionsUtil.parseActor(p.one("actor"));
        return NaruAccessRequest.of(resource, action, actor).task(context.task());
    }

    static NaruStmtResult check0(NaruDirectiveCallContext context, Parsed p, boolean explain) {
        NaruAccessPolicy policy = policy(context);
        NaruAccessRequest request = request(context, p);
        NaruAccessDecision d = explain ? policy.explain(request) : policy.check(request);
        StringBuilder sb = new StringBuilder();
        sb.append(d.allowed() ? "ALLOW" : "DENY")
                .append(' ').append(request.resource().type().id())
                .append(' ').append(request.resource().locator())
                .append(' ').append(request.action().id())
                .append(" for ").append(request.actor());
        if (d.matchedRule().isPresent()) {
            sb.append("\n  matched: ").append(NaruPermissionsUtil.describeRule(d.matchedRule().get()));
        } else {
            sb.append("\n  matched: <default> (").append(d.effect().name().toLowerCase()).append(')');
        }
        if (!NBlankable.isBlank(d.reason())) {
            sb.append("\n  reason: ").append(d.reason());
        }
        if (explain) {
            for (NaruAccessTrace t : d.trace()) {
                sb.append("\n  ").append(t.matched() ? "MATCH " : "skip  ");
                if (t.rule() != null) {
                    sb.append(NaruPermissionsUtil.describeRule(t.rule()));
                }
                if (!NBlankable.isBlank(t.detail())) {
                    sb.append("  -- ").append(t.detail());
                }
            }
        }
        String text = sb.toString();
        log(context, NMsg.ofNtf(text));
        // "check" is a question, so a denied answer is a failed statement a script can
        // branch on; "explain" is a report and always succeeds.
        return (!explain && !d.allowed()) ? NaruStmtResult.ofError(text) : NaruStmtResult.ofSuccess(null);
    }

    static final class Parsed {
        final List<String> args = new ArrayList<>();
        final Map<String, List<String>> opts = new LinkedHashMap<>();

        void put(String key, String value) {
            opts.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        boolean has(String key) {
            return opts.containsKey(key);
        }

        String one(String key) {
            List<String> l = opts.get(key);
            return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
        }

        List<String> all(String key) {
            List<String> l = opts.get(key);
            return l == null ? List.of() : l;
        }

        /** Adapts a tool's argument map to the same shape the directive parses to. */
        static Parsed of(Map<String, Object> args) {
            Parsed p = new Parsed();
            if (args != null) {
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Iterable) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : (Iterable<?>) v) {
                            if (sb.length() > 0) {
                                sb.append(',');
                            }
                            sb.append(o);
                        }
                        p.put(e.getKey(), sb.toString());
                    } else {
                        p.put(e.getKey(), String.valueOf(v));
                    }
                }
            }
            return p;
        }
    }

    static Parsed parse(NCmdLine cmdLine) {
        Parsed p = new Parsed();
        while (!cmdLine.isEmpty()) {
            String img = cmdLine.next().get().image();
            if (img.startsWith("--")) {
                String body = img.substring(2);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    p.put(body.substring(0, eq), body.substring(eq + 1));
                } else {
                    String value = "true";
                    if (!cmdLine.isEmpty()) {
                        String next = cmdLine.peek().get().image();
                        if (!next.startsWith("--")) {
                            value = next;
                            cmdLine.next();
                        }
                    }
                    p.put(body, value);
                }
            } else {
                p.args.add(img);
            }
        }
        return p;
    }
}
