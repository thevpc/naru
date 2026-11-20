package net.thevpc.naru.api.project;

import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.elem.NToElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NIllegalArgumentException;

import java.text.Normalizer;
import java.time.Instant;

/**
 * A named grouping of sessions that supplies <em>initial</em> values to new sessions.
 *
 * <p>A project is <b>semantic, not restrictive</b>: it carries a {@code folder} and a
 * {@code user}, and when a session is created under it those values are <b>copied</b>
 * into the session (folder to the session's {@code sessionDir}, user to the session's
 * {@code user}). After creation the session is independent; editing the project never
 * touches existing sessions, and moving a session between projects changes only the
 * session's {@code projectId}, never its storage location or its directory.
 *
 * <p>Projects are flat: there is no parent project, no nesting. A project does not own
 * sessions: {@code session.projectId} is the only link between the two, and a session
 * with no project id is a valid standalone session.
 *
 * <p>Distinct from the session directory: {@code folder} is this project's advertised
 * workspace location; the {@code sessionDir} copied from it is a per-session anchor used
 * for skills and configuration, and the session's <em>storage</em> folder is unrelated to
 * both. {@code workingDir} (where shell and file tools run) is never derived from a
 * project.
 *
 * <h2>Field rules</h2>
 * <ul>
 *   <li>{@code id} — a UUID string assigned at creation, immutable.</li>
 *   <li>{@code name} — required; trimmed, Unicode NFC-normalized, non-empty, at most
 *       200 characters, no control characters.</li>
 *   <li>{@code folder} — required; absolute and normalized
 *       ({@code toAbsolutePath().normalize()}). Existence is not enforced: a folder on an
 *       unmounted volume is storable, and callers get a warning rather than an error.</li>
 *   <li>{@code user} — nullable, opaque. Trimmed; an empty string becomes {@code null}.
 *       It carries no authentication or permissions; it is stored and filterable only.</li>
 *   <li>{@code icon} — nullable presentation key, at most {@value #MAX_ICON_LENGTH}
 *       characters. Hosts map it to a glyph of their own choosing, so an unknown icon is
 *       ignored rather than rejected. Never interpreted by the engine.</li>
 *   <li>{@code color} — nullable {@code #RRGGBB}, stored upper-case. A literal colour and
 *       not a preset name so that a host which has never seen the picker still paints it.</li>
 *   <li>{@code description} — nullable free text, at most {@value #MAX_DESCRIPTION_LENGTH}
 *       characters, line breaks allowed. Display only.</li>
 *   <li>{@code createdAt} — set once. {@code updatedAt} — set on every successful write.
 *       {@code version} — starts at 1, incremented on every successful write; the token
 *       for optimistic concurrency.</li>
 * </ul>
 *
 * <h2>Uniqueness</h2>
 * {@code (user, name)} is unique, names compared case-insensitively after normalization;
 * a {@code null} user is its own namespace. Folders are not unique: two projects may
 * point at the same folder.
 */
public final class NaruProject implements NToElement {

    /** The longest name the type accepts. */
    public static final int MAX_NAME_LENGTH = 200;
    /** The longest icon key the type accepts. */
    public static final int MAX_ICON_LENGTH = 64;
    /** The longest description the type accepts. */
    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    private final String id;
    private final String name;
    private final NPath folder;
    private final String user;
    private final String icon;
    private final String color;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private NaruProject(String id, String name, NPath folder, String user,
                        String icon, String color, String description,
                        Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.name = name;
        this.folder = folder;
        this.user = user;
        this.icon = icon;
        this.color = color;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Builds a validated project. Name and user are normalized as the type's rules
     * require; the folder is made absolute and normalized.
     *
     * @throws NIllegalArgumentException when a field violates the rules
     */
    public static NaruProject of(String id, String name, NPath folder, String user,
                                 String icon, String color, String description,
                                 Instant createdAt, Instant updatedAt, long version) {
        if (id == null || id.isBlank()) {
            throw new NIllegalArgumentException(NMsg.ofC("project id is required"));
        }
        if (createdAt == null || updatedAt == null) {
            throw new NIllegalArgumentException(NMsg.ofC("project %s requires creation and update instants", id));
        }
        if (version < 1) {
            throw new NIllegalArgumentException(NMsg.ofC("project %s version must be at least 1, found %s",
                    id, version));
        }
        return new NaruProject(id, checkName(name), checkFolder(folder), checkUser(user),
                checkIcon(icon), checkColor(color), checkDescription(description),
                createdAt, updatedAt, version);
    }

    /**
     * Returns this project with a different version token and update time, fields
     * validated. Used by stores when a write succeeds; validates the same rules as
     * {@link #of}, so a patch cannot smuggle in a bad value.
     */
    public NaruProject written(String name, NPath folder, String user,
                               String icon, String color, String description,
                               Instant updatedAt, long newVersion) {
        return of(id, name, folder, user, icon, color, description, createdAt, updatedAt, newVersion);
    }

    // ---------------------------------------------------------------- rules

    /** Trimmed, NFC-normalized; throws when the result is not a valid project name. */
    public static String checkName(String name) {
        if (name == null) {
            throw new NIllegalArgumentException(NMsg.ofC("project name is required"));
        }
        String normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFC);
        if (normalized.isEmpty()) {
            throw new NIllegalArgumentException(NMsg.ofC("project name is required (blank after trim)"));
        }
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new NIllegalArgumentException(NMsg.ofC("project name exceeds %s characters: %s",
                    MAX_NAME_LENGTH, abbreviated(normalized)));
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.getType(normalized.charAt(i)) == Character.FORMAT
                    || Character.isISOControl(normalized.charAt(i))) {
                throw new NIllegalArgumentException(NMsg.ofC("project name contains control characters: %s",
                        abbreviated(normalized)));
            }
        }
        return normalized;
    }

    /** Absolute, normalized; existence is deliberately not checked (a warning is the caller's job). */
    public static NPath checkFolder(NPath folder) {
        if (folder == null) {
            throw new NIllegalArgumentException(NMsg.ofC("project folder is required"));
        }
        return folder.toAbsolute().normalize();
    }

    /** Trimmed; an empty string becomes null. Null stays null: it is its own namespace. */
    public static String checkUser(String user) {
        if (user == null) {
            return null;
        }
        String trimmed = Normalizer.normalize(user.trim(), Normalizer.Form.NFC);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The comparison form for uniqueness and idOrName lookup: NFC, trimmed, lower-case. */
    public static String normalizedNameKey(String name) {
        return checkName(name).toLowerCase(java.util.Locale.ROOT);
    }

    /** Trimmed and NFC-normalized; blank becomes null. No line breaks: an icon is one short word. */
    public static String checkIcon(String icon) {
        return checkText(icon, MAX_ICON_LENGTH, "icon", false);
    }

    /** Trimmed, NFC-normalized, line breaks kept; blank becomes null. */
    public static String checkDescription(String description) {
        return checkText(description, MAX_DESCRIPTION_LENGTH, "description", true);
    }

    /**
     * {@code #RRGGBB}, stored upper-case so an edit that keeps the colour does not report a
     * change; blank and null both mean "no colour chosen".
     */
    public static String checkColor(String color) {
        if (color == null) {
            return null;
        }
        String trimmed = Normalizer.normalize(color.trim(), Normalizer.Form.NFC);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() != 7 || trimmed.charAt(0) != '#') {
            throw new NIllegalArgumentException(NMsg.ofC("project color must be #RRGGBB, found %s",
                    abbreviated(trimmed)));
        }
        for (int i = 1; i < 7; i++) {
            if (Character.digit(trimmed.charAt(i), 16) < 0) {
                throw new NIllegalArgumentException(NMsg.ofC("project color must be #RRGGBB, found %s",
                        abbreviated(trimmed)));
            }
        }
        return trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static String checkText(String value, int maxLength, String field, boolean multiline) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new NIllegalArgumentException(NMsg.ofC("project %s exceeds %s characters: %s",
                    field, maxLength, abbreviated(normalized)));
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!multiline && (c == '\n' || c == '\r')) {
                throw new NIllegalArgumentException(NMsg.ofC("project %s must be a single line: %s",
                        field, abbreviated(normalized)));
            }
            if (Character.getType(c) == Character.FORMAT || Character.isISOControl(c)) {
                if (multiline && (c == '\n' || c == '\r' || c == '\t')) {
                    continue;
                }
                throw new NIllegalArgumentException(NMsg.ofC("project %s contains control characters: %s",
                        field, abbreviated(normalized)));
            }
        }
        return normalized;
    }

    private static String abbreviated(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    // ---------------------------------------------------------------- accessors

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public NPath folder() {
        return folder;
    }

    /** The opaque owner, or null. No authentication, no permissions: stored and filterable only. */
    public String user() {
        return user;
    }

    /** The presentation icon key, or null. Hosts are free to ignore an unknown key. */
    public String icon() {
        return icon;
    }

    /** The {@code #RRGGBB} accent, or null when the host should use its own default. */
    public String color() {
        return color;
    }

    /** Free text for the host to display, or null. Never parsed. */
    public String description() {
        return description;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** The optimistic-concurrency token: 1 at creation, incremented on every successful write. */
    public long version() {
        return version;
    }

    // ---------------------------------------------------------------- element

    public static NaruProject of(NElement element) {
        NObjectElement o = element.asObject().orElseThrow(
                () -> new NIllegalArgumentException(NMsg.ofC("a project element must be an object")));
        return of(o.getStringValue("id").orElse(null),
                o.getStringValue("name").orElse(null),
                o.getStringValue("folder").map(NPath::of).orElse(null),
                o.getStringValue("user").orElse(null),
                o.getStringValue("icon").orElse(null),
                o.getStringValue("color").orElse(null),
                o.getStringValue("description").orElse(null),
                o.getInstantValue("createdAt").orElse(null),
                o.getInstantValue("updatedAt").orElse(null),
                o.getLongValue("version").orElse(0L));
    }

    @Override
    public NElement toElement() {
        NObjectElementBuilder o = NObjectElementBuilder.of();
        o.set("id", id);
        o.set("name", name);
        o.set("folder", folder == null ? null : folder.toString());
        if (user != null) {
            // null user is omitted rather than written as a null value: "no owner" is
            // the absence of the key, so a file reader cannot confuse it with a stored
            // empty string
            o.set("user", user);
        }
        if (icon != null) {
            o.set("icon", icon);
        }
        if (color != null) {
            o.set("color", color);
        }
        if (description != null) {
            o.set("description", description);
        }
        o.set("createdAt", NElement.ofInstant(createdAt));
        o.set("updatedAt", NElement.ofInstant(updatedAt));
        o.set("version", version);
        return o.build();
    }

    @Override
    public String toString() {
        return "NaruProject{id=" + id + ", name=" + name + ", folder=" + folder
                + ", user=" + user + ", icon=" + icon + ", color=" + color
                + ", version=" + version + '}';
    }
}
