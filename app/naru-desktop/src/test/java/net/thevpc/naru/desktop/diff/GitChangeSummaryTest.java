package net.thevpc.naru.desktop.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitChangeSummaryTest {

    @Test
    void stagedAndWorkingTreeCountsMergePerFile() {
        GitChangeSummary.Snapshot snapshot = GitChangeSummary.merge(
                "1\t2\tsrc/A.java\n5\t0\tsrc/B.java\n",
                "3\t4\tsrc/A.java\n");

        assertEquals(2, snapshot.fileCount());
        assertEquals(9, snapshot.added());
        assertEquals(6, snapshot.deleted());
        assertEquals("src/A.java", snapshot.files().get(0).path());
        assertEquals(4, snapshot.files().get(0).added());
        assertEquals(6, snapshot.files().get(0).deleted());
        assertEquals(5, snapshot.files().get(1).added());
    }

    @Test
    void binaryFilesAreCountedButDoNotInventLineChanges() {
        GitChangeSummary.Snapshot snapshot = GitChangeSummary.merge(
                "-\t-\tassets/logo.png\n2\t1\tsrc/A.java\n", "");

        assertEquals(2, snapshot.fileCount());
        assertEquals(2, snapshot.added());
        assertEquals(1, snapshot.deleted());
        assertTrue(snapshot.files().get(0).binary());
        assertFalse(snapshot.files().get(1).binary());
    }

    @Test
    void nothingChangedIsAnEmptySnapshot() {
        GitChangeSummary.Snapshot snapshot = GitChangeSummary.merge("", "");
        assertTrue(snapshot.files().isEmpty());
        assertFalse(snapshot.hasChanges());
        assertEquals(0, snapshot.added());
        assertEquals(0, snapshot.deleted());
    }
}
