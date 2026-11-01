package net.thevpc.naru.api.project;

import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * The field rules of the project types, tested as pure values: what the store will
 * refuse and what it is entitled to assume has already been normalized. Unicode NFC,
 * case-insensitive comparison keys, blank-user-as-null, and the unset-versus-clear
 * distinction in a patch are all decided here, once, so no implementation has to
 * re-decide them.
 */
public class NaruProjectValueTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2024-01-02T00:00:00Z");

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruProject project(String name, NPath folder, String user) {
        return NaruProject.of("aaaaaaaa-0000-0000-0000-000000000001", name, folder, user,
                null, null, null, T0, T1, 1);
    }

    // ---------------------------------------------------------------- name

    @Test
    public void nameIsTrimmedAndNfcNormalized() {
        // "café" pre-composed (é as U+00E9) and decomposed (e + U+0301) are the same name
        String composed = "caf\u00e9";
        String decomposed = "cafe\u0301";
        NaruProject p = project("  " + decomposed + "  ", NPath.of("/tmp/x"), null);
        Assertions.assertEquals(composed, p.name());
        Assertions.assertEquals(NaruProject.normalizedNameKey(composed),
                NaruProject.normalizedNameKey(decomposed));
    }

    @Test
    public void blankOrTooLongOrControlNamesAreRefused() {
        Assertions.assertThrows(NIllegalArgumentException.class, () -> project("   ", NPath.of("/tmp/x"), null));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> project("n".repeat(NaruProject.MAX_NAME_LENGTH + 1), NPath.of("/tmp/x"), null));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> project("bad\u0007name", NPath.of("/tmp/x"), null));
        // exactly the maximum is fine
        Assertions.assertEquals("n".repeat(NaruProject.MAX_NAME_LENGTH),
                project("n".repeat(NaruProject.MAX_NAME_LENGTH), NPath.of("/tmp/x"), null).name());
    }

    @Test
    public void missingIdOrVersionBelowOneIsRefused() {
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of(" ", "name", NPath.of("/tmp/x"), null, null, null, null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "name", NPath.of("/tmp/x"), null, null, null, null, T0, T1, 0));
    }

    // ---------------------------------------------------------------- user

    @Test
    public void userIsTrimmedAndBlankBecomesNull() {
        Assertions.assertEquals("alice", project("n", NPath.of("/tmp/x"), "  alice ").user());
        Assertions.assertNull(project("n", NPath.of("/tmp/x"), "   ").user());
        Assertions.assertNull(project("n", NPath.of("/tmp/x"), null).user());
    }

    // ---------------------------------------------------------------- folder

    @Test
    public void folderIsMadeAbsoluteAndNormalized() {
        NaruProject p = project("n", NPath.of("sub/../other"), null);
        NPath expected = NPath.of("sub/../other").toAbsolute().normalize();
        Assertions.assertEquals(expected, p.folder());
        Assertions.assertTrue(p.folder().isAbsolute());
    }

    @Test
    public void missingFolderIsRefused() {
        Assertions.assertThrows(NIllegalArgumentException.class, () -> project("n", null, null));
    }

    // ---------------------------------------------------------------- versions and elements

    @Test
    public void writtenProducesARevalidatedRowWithTheNewVersion() {
        NaruProject p = project("first", NPath.of("/tmp/a"), "alice");
        NaruProject q = p.written("second", p.folder(), null, p.icon(), p.color(), p.description(),
                T1.plusSeconds(60), 2);
        Assertions.assertEquals("second", q.name());
        Assertions.assertEquals(p.id(), q.id());
        Assertions.assertEquals(p.createdAt(), q.createdAt());
        Assertions.assertNull(q.user());
        Assertions.assertEquals(2, q.version());
        // revalidation still applies
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> p.written("", p.folder(), null, null, null, null, T1, 2));
    }

    @Test
    public void presentationFieldsAreTrimmedOrRefused() {
        NaruProject p = NaruProject.of("aaaaaaaa-0000-0000-0000-000000000002", "n", NPath.of("/tmp/x"),
                null, "  rocket ", "#3b82f6", "  the api work \n second line ", T0, T1, 1);
        Assertions.assertEquals("rocket", p.icon());
        // a colour reads back upper-case so re-stating it is not reported as a change
        Assertions.assertEquals("#3B82F6", p.color());
        Assertions.assertEquals("the api work \n second line", p.description());
        Assertions.assertNull(NaruProject.of("id", "n", NPath.of("/tmp/x"), null, "  ", "#FFFFFF", "   ",
                T0, T1, 1).icon());
        // an icon names one glyph, so a line break in it is a mistake rather than prose
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, "two\nlines", null, null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, "i".repeat(NaruProject.MAX_ICON_LENGTH + 1),
                        null, null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, null, "red", null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, null, "#3B82F", null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, null, "#GGGGGG", null, T0, T1, 1));
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> NaruProject.of("id", "n", NPath.of("/tmp/x"), null, null, null,
                        "d".repeat(NaruProject.MAX_DESCRIPTION_LENGTH + 1), T0, T1, 1));
    }

    @Test
    public void unsetPresentationFieldsAreOmittedAndReadBackAsNull() {
        NaruProject p = project("solo", NPath.of("/tmp/x"), null);
        NElement e = p.toElement();
        Assertions.assertNull(NaruProject.of(e).icon());
        Assertions.assertNull(NaruProject.of(e).color());
        Assertions.assertNull(NaruProject.of(e).description());
    }

    @Test
    public void elementRoundTripPreservesEveryField() {
        NaruProject p = NaruProject.of("aaaaaaaa-0000-0000-0000-000000000003", "café", NPath.of("/tmp/x"),
                "bob", "rocket", "#3B82F6", "the api work", T0, T1, 1);
        NElement e = p.toElement();
        NaruProject back = NaruProject.of(e);
        Assertions.assertEquals(p.id(), back.id());
        Assertions.assertEquals(p.name(), back.name());
        Assertions.assertEquals(p.folder(), back.folder());
        Assertions.assertEquals(p.user(), back.user());
        Assertions.assertEquals(p.icon(), back.icon());
        Assertions.assertEquals(p.color(), back.color());
        Assertions.assertEquals(p.description(), back.description());
        Assertions.assertEquals(p.createdAt(), back.createdAt());
        Assertions.assertEquals(p.updatedAt(), back.updatedAt());
        Assertions.assertEquals(p.version(), back.version());
    }

    @Test
    public void nullUserIsOmittedFromTheElementAndReadsBackAsNull() {
        NaruProject p = project("solo", NPath.of("/tmp/x"), null);
        NElement e = p.toElement();
        Assertions.assertTrue(e.asObject().get().getStringValue("user").isEmpty()
                || e.asObject().get().getStringValue("user").orElse("x") == null);
        Assertions.assertNull(NaruProject.of(e).user());
    }

    // ---------------------------------------------------------------- patch

    @Test
    public void patchDistinguishesUnsetFromExplicitClear() {
        NaruProjectPatch empty = NaruProjectPatch.of();
        Assertions.assertTrue(empty.isEmpty());
        Assertions.assertFalse(empty.user().isSet());

        NaruProjectPatch clear = NaruProjectPatch.of().clearUser();
        Assertions.assertTrue(clear.user().isSet());
        Assertions.assertNull(clear.user().value());

        NaruProjectPatch named = NaruProjectPatch.of().name("renamed").user("carol");
        Assertions.assertTrue(named.name().isSet());
        Assertions.assertEquals("renamed", named.name().value());
        Assertions.assertEquals("carol", named.user().value());
        Assertions.assertFalse(named.folder().isSet(), "an untouched field stays unset");

        NaruProjectPatch props = NaruProjectPatch.of().icon("rocket").color("#3B82F6").description("api work");
        Assertions.assertEquals("rocket", props.icon().value());
        Assertions.assertEquals("#3B82F6", props.color().value());
        Assertions.assertEquals("api work", props.description().value());
        Assertions.assertFalse(props.name().isSet(), "a props-only patch leaves the name alone");
        // clearing a presentation field is a statement, not an omission: the store has to
        // remove the colour rather than keep the one the row already carries
        NaruProjectPatch cleared = NaruProjectPatch.of().clearColor().clearIcon().clearDescription();
        Assertions.assertTrue(cleared.color().isSet() && cleared.icon().isSet() && cleared.description().isSet());
        Assertions.assertNull(cleared.color().value());
        Assertions.assertFalse(NaruProjectPatch.of().isEmpty() && cleared.isEmpty());
    }

    @Test
    public void settingOfNullValueIsStillASet() {
        NaruSetting<String> s = NaruSetting.set(null);
        Assertions.assertTrue(s.isSet());
        Assertions.assertNull(s.value());
        Assertions.assertFalse(NaruSetting.unset().isSet());
    }
}
