package de.tum.cit.aet.artemis.hyperion.mcq.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusUploadServiceTest {

    @TempDir
    Path root;

    @Test
    void keepsAPathThatStaysInsideTheRoot() {
        assertThat(CorpusUploadService.safeResolve(root, "05 Duality/deck.pdf")).isPresent().get().isEqualTo(root.resolve("05 Duality/deck.pdf"));
    }

    @Test
    void refusesAPathThatClimbsOutOfTheRoot() {
        // Zip slip: an archive entry that would write outside the staging directory.
        assertThat(CorpusUploadService.safeResolve(root, "../escaped.pdf")).isEmpty();
        assertThat(CorpusUploadService.safeResolve(root, "a/../../escaped.pdf")).isEmpty();
        assertThat(CorpusUploadService.safeResolve(root, "../../../../etc/authorized_keys")).isEmpty();
    }

    @Test
    void refusesAnAbsolutePathByTreatingItAsRelative() {
        // A leading slash is stripped rather than honoured, so it cannot address the filesystem root.
        assertThat(CorpusUploadService.safeResolve(root, "/etc/passwd")).isPresent().get().isEqualTo(root.resolve("etc/passwd"));
    }

    @Test
    void refusesAWindowsStyleDriveLetter() {
        assertThat(CorpusUploadService.safeResolve(root, "C:/windows/system32/x.pdf")).isEmpty();
    }

    @Test
    void normalisesBackslashesSoAnArchiveMadeOnWindowsKeepsItsStructure() {
        assertThat(CorpusUploadService.safeResolve(root, "05 Duality\\deck.pdf")).isPresent().get().isEqualTo(root.resolve("05 Duality/deck.pdf"));
    }

    @Test
    void refusesAnEmptyOrRootPath() {
        assertThat(CorpusUploadService.safeResolve(root, "")).isEmpty();
        assertThat(CorpusUploadService.safeResolve(root, "/")).isEmpty();
        assertThat(CorpusUploadService.safeResolve(root, ".")).isEmpty();
    }

    @Test
    void derivesTheLectureFromTheFirstPathElement() {
        assertThat(new CorpusUploadService.StagedFile("05 Duality/deck.pdf", 1).lecture()).isEqualTo("05 Duality");
        assertThat(new CorpusUploadService.StagedFile("nested/deeper/deck.pdf", 1).lecture()).isEqualTo("nested");
    }

    @Test
    void namesLooseFilesAsRootSoTheyAreVisiblyUnattributed() {
        // A flat upload has no lecture, which the preview must show rather than silently accept.
        assertThat(new CorpusUploadService.StagedFile("deck.pdf", 1).lecture()).isEqualTo("(root)");
    }

    @Test
    void summarisesLecturesAndTotalSizeOfAStagedUpload() {
        var staged = new CorpusUploadService.Staged("abc", java.util.List.of(new CorpusUploadService.StagedFile("A/one.pdf", 100),
                new CorpusUploadService.StagedFile("A/two.pdf", 200), new CorpusUploadService.StagedFile("B/three.pdf", 300)), java.util.List.of());

        assertThat(staged.lectures()).containsExactly("A", "B");
        assertThat(staged.totalBytes()).isEqualTo(600);
    }
}
