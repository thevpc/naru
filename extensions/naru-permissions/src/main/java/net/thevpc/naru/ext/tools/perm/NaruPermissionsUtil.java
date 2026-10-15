package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared parsing and rendering for the {@code /permission} directive and the
 * permission tools.
 *
 * <p>Both surfaces accept the same vocabulary, so the conversion from a typed
 * token to a value type lives once here: two parsers would eventually disagree
 * about whether {@code r} means read or whether {@code agent} names a kind or a
 * principal, and the disagreement would be invisible until a denied call let
 * something through.
 */
public final class NaruPermissionsUtil {

    private NaruPermissionsUtil() {
    }

    /** Splits a comma/semicolon/space separated list, dropping blanks. */
    public static List<String> splitList(String s) {
        if (NBlankable.isBlank(s)) {
            return List.of();
        }
        return NStringUtils.split(s, ", ;", true, true);
    }

    public static NaruResourceType parseResourceType(String token) {
        if (NBlankable.isBlank(token)) {
            return null;
        }
        return NaruResourceType.of(token.trim());
    }

    /** Parses {@code r,w} (or a single {@code r}) into an action set; empty means "any". */
    public static Set<NaruAction> parseActions(String token) {
        Set<NaruAction> actions = new LinkedHashSet<>();
        for (String a : splitList(token)) {
            actions.add(NaruAction.parse(a));
        }
        return actions;
    }

    public static List<NaruSubject> parseSubjects(String token) {
        List<NaruSubject> out = new ArrayList<>();
        for (String s : splitList(token)) {
            out.add(NaruSubject.parse(s));
        }
        return out;
    }

    public static NaruActorKind parseActorKind(String token) {
        if (NBlankable.isBlank(token)) {
            return null;
        }
        String t = token.trim().toUpperCase();
        if ("ANY".equals(t) || "*".equals(t)) {
            return null;
        }
        try {
            return NaruActorKind.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new NIllegalArgumentException(NMsg.ofC("unknown actor kind '%s'", token));
        }
    }

    public static NaruAccessEffect parseEffect(String token) {
        if (NBlankable.isBlank(token)) {
            return NaruAccessEffect.ALLOW;
        }
        String t = token.trim().toUpperCase();
        if ("TRUE".equals(t) || "YES".equals(t)) {
            return NaruAccessEffect.ALLOW;
        }
        if ("FALSE".equals(t) || "NO".equals(t)) {
            return NaruAccessEffect.DENY;
        }
        try {
            return NaruAccessEffect.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new NIllegalArgumentException(NMsg.ofC("unknown effect '%s'", token));
        }
    }

    /**
     * Parses an actor shorthand for {@code --actor}:
     * {@code user:alice}, {@code agent:reviewer}, {@code agent:reviewer@alice},
     * {@code system}, or a bare id (a user).
     */
    public static NaruActor parseActor(String token) {
        if (NBlankable.isBlank(token)) {
            return null;
        }
        String s = token.trim();
        if ("system".equalsIgnoreCase(s)) {
            return NaruActor.system();
        }
        int idx = s.indexOf(':');
        if (idx > 0) {
            String kind = s.substring(0, idx).trim().toLowerCase();
            String rest = s.substring(idx + 1).trim();
            switch (kind) {
                case "user":
                    return NaruActor.user(NaruPrincipal.user(rest));
                case "agent": {
                    int at = rest.indexOf('@');
                    if (at >= 0) {
                        return NaruActor.agent(rest.substring(0, at), NaruPrincipal.user(rest.substring(at + 1)));
                    }
                    return NaruActor.agent(rest, null);
                }
                default:
                    throw new NIllegalArgumentException(NMsg.ofC("unknown actor '%s'", token));
            }
        }
        return NaruActor.user(NaruPrincipal.user(s));
    }

    /** Builds the resource a {@code check}/{@code explain} query is about. */
    public static NaruResource resourceOf(String typeToken, String locator) {
        NaruResourceType type = parseResourceType(typeToken);
        if (NBlankable.isBlank(locator)) {
            throw new NIllegalArgumentException(NMsg.ofC("a resource locator is required"));
        }
        return NaruResource.of(type == null ? NaruResourceType.ANY : type, locator.trim());
    }

    /** One human-readable line per rule, the shape both {@code list} surfaces show. */
    public static String describeRule(NaruPermissionRule r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.id() == null ? "<no-id>" : r.id());
        sb.append(" [").append(r.effect() == null ? "ALLOW" : r.effect().name().toLowerCase()).append("] ");
        sb.append("type=").append(r.resourceType() == null ? "*" : r.resourceType().id());
        sb.append(" patterns=").append(r.patterns().isEmpty() ? "*" : String.join(",", r.patterns()));
        Set<NaruAction> actions = r.actions();
        if (actions == null || actions.isEmpty()) {
            sb.append(" actions=*");
        } else {
            List<String> ids = new ArrayList<>();
            for (NaruAction a : actions) {
                ids.add(a.id());
            }
            sb.append(" actions=").append(String.join(",", ids));
        }
        sb.append(" subjects=").append(describeSubjects(r));
        if (r.actorKind() != null) {
            sb.append(" kind=").append(r.actorKind().name().toLowerCase());
        }
        sb.append(" order=").append(r.order());
        if (r.source() != null) {
            sb.append(" source=").append(r.source());
        }
        if (!r.enabled()) {
            sb.append(" (disabled)");
        }
        if (!NBlankable.isBlank(r.description())) {
            sb.append(" -- ").append(r.description());
        }
        return sb.toString();
    }

    private static String describeSubjects(NaruPermissionRule r) {
        if (r.subjects() == null || r.subjects().isEmpty()) {
            return "*";
        }
        StringBuilder sb = new StringBuilder();
        for (NaruSubject s : r.subjects()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.type() == null ? "*" : s.type().name().toLowerCase());
            if (!NBlankable.isBlank(s.id())) {
                sb.append(':').append(s.id());
            }
        }
        return sb.toString();
    }
}
