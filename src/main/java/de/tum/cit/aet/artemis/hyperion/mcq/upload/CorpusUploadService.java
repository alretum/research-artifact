package de.tum.cit.aet.artemis.hyperion.mcq.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusIndexService;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader.DocumentReport;

/**
 * Accepts lecture material through the browser, into a staging area first.
 * <p>
 * Staged rather than written straight into the corpus, because the corpus convention carries meaning: the
 * first directory level names the lecture, and material landing at the wrong level is attributed to the
 * wrong lecture in every citation afterwards. Staging makes that visible before it is committed, and a
 * partly failed upload leaves the corpus untouched rather than half-populated.
 */
@Service
public class CorpusUploadService {

    private static final Logger log = LoggerFactory.getLogger(CorpusUploadService.class);

    /** Only PDFs are ingested, and a zip is the reliable way to preserve a directory structure. */
    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of("pdf", "zip");

    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;

    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private static final int MAX_ENTRIES = 5_000;

    private final PipelineProperties properties;

    private final CorpusIndexService corpus;

    public CorpusUploadService(PipelineProperties properties, CorpusIndexService corpus) {
        this.properties = properties;
        this.corpus = corpus;
    }

    /**
     * @param path  corpus-relative path the file will occupy
     * @param bytes its size
     */
    public record StagedFile(String path, long bytes) {

        /** @return the lecture this file would belong to, or {@code "(root)"} when it has no directory */
        public String lecture() {
            int slash = path.indexOf('/');
            return slash < 0 ? "(root)" : path.substring(0, slash);
        }
    }

    /**
     * @param uploadId identifier of the staging area
     * @param files    files accepted into staging
     * @param rejected human-readable reasons for anything refused
     */
    public record Staged(String uploadId, List<StagedFile> files, List<String> rejected) {

        /** @return the lectures this upload would create or add to */
        public List<String> lectures() {
            return files.stream().map(StagedFile::lecture).distinct().sorted().toList();
        }

        public long totalBytes() {
            return files.stream().mapToLong(StagedFile::bytes).sum();
        }
    }

    /**
     * @param uploadId  the staging area
     * @param documents what extraction found in each staged PDF
     * @param existing  lectures already in the corpus that this upload would add to
     */
    public record Preview(String uploadId, List<DocumentReport> documents, List<String> existing) {
    }

    /**
     * Take uploaded files into a fresh staging area without touching the corpus.
     *
     * @param files         uploaded parts; a {@code .zip} is expanded, a {@code .pdf} is taken as-is
     * @param relativePaths corpus-relative path per file, positionally matched, as a browser directory
     *                      picker supplies. May be null or shorter than {@code files}, in which case the
     *                      part's own filename is used
     * @return what was staged and what was refused
     */
    public Staged stage(List<MultipartFile> files, List<String> relativePaths) {
        String uploadId = UUID.randomUUID().toString().substring(0, 8);
        Path staging = stagingRoot().resolve(uploadId);
        List<StagedFile> staged = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        long total = 0;

        try {
            Files.createDirectories(staging);
            for (int i = 0; i < files.size(); i++) {
                MultipartFile part = files.get(i);
                if (part.isEmpty()) {
                    continue;
                }
                String declared = relativePaths != null && i < relativePaths.size() && relativePaths.get(i) != null && !relativePaths.get(i).isBlank() ? relativePaths.get(i)
                        : part.getOriginalFilename();
                String name = declared == null ? "" : declared;
                String extension = extensionOf(name);

                if (!ACCEPTED_EXTENSIONS.contains(extension)) {
                    rejected.add(name + " — only .pdf and .zip are accepted");
                    continue;
                }
                if (part.getSize() > MAX_FILE_BYTES) {
                    rejected.add(name + " — larger than " + MAX_FILE_BYTES / 1024 / 1024 + " MB");
                    continue;
                }
                total += part.getSize();
                if (total > MAX_TOTAL_BYTES) {
                    rejected.add(name + " — upload exceeds " + MAX_TOTAL_BYTES / 1024 / 1024 + " MB in total");
                    break;
                }

                if ("zip".equals(extension)) {
                    try (InputStream in = part.getInputStream()) {
                        staged.addAll(expand(in, staging, rejected));
                    }
                }
                else {
                    safeResolve(staging, name).ifPresentOrElse(target -> {
                        try {
                            Files.createDirectories(target.getParent());
                            part.transferTo(target);
                            staged.add(new StagedFile(staging.relativize(target).toString(), part.getSize()));
                        }
                        catch (IOException e) {
                            rejected.add(name + " — could not be written: " + e.getMessage());
                        }
                    }, () -> rejected.add(name + " — rejected: the path escapes the upload directory"));
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to stage upload " + uploadId, e);
        }

        log.info("Upload {}: staged {} file(s), refused {}", uploadId, staged.size(), rejected.size());
        return new Staged(uploadId, List.copyOf(staged), List.copyOf(rejected));
    }

    /**
     * Expand a zip into staging.
     * <p>
     * Every entry path is resolved and checked against the staging root before anything is written, because
     * an archive may contain {@code ../} segments or absolute paths that would otherwise write anywhere on
     * the host. Decompressed size is counted as it is read, since the compressed size is no bound on it.
     */
    private List<StagedFile> expand(InputStream in, Path staging, List<String> rejected) throws IOException {
        List<StagedFile> staged = new ArrayList<>();
        long decompressed = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    rejected.add("archive stopped after " + MAX_ENTRIES + " entries");
                    break;
                }
                String name = entry.getName();
                if (!"pdf".equals(extensionOf(name))) {
                    continue;
                }
                var target = safeResolve(staging, name);
                if (target.isEmpty()) {
                    rejected.add(name + " — rejected: the path escapes the upload directory");
                    continue;
                }
                Files.createDirectories(target.get().getParent());
                long written = Files.copy(zip, target.get(), StandardCopyOption.REPLACE_EXISTING);
                decompressed += written;
                if (decompressed > MAX_TOTAL_BYTES) {
                    rejected.add("archive stopped: expands beyond " + MAX_TOTAL_BYTES / 1024 / 1024 + " MB");
                    Files.deleteIfExists(target.get());
                    break;
                }
                staged.add(new StagedFile(staging.relativize(target.get()).toString(), written));
            }
        }
        return staged;
    }

    /**
     * Resolve a declared path inside a root, refusing anything that escapes it.
     *
     * @return the resolved path, or empty when the declared path leaves {@code root}
     */
    static java.util.Optional<Path> safeResolve(Path root, String declared) {
        String cleaned = Normalizer.normalize(declared.replace('\\', '/'), Normalizer.Form.NFC);
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank() || cleaned.contains(":")) {
            return java.util.Optional.empty();
        }
        Path candidate = root.resolve(cleaned).normalize();
        Path normalisedRoot = root.normalize();
        return candidate.startsWith(normalisedRoot) && !candidate.equals(normalisedRoot) ? java.util.Optional.of(candidate) : java.util.Optional.empty();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Run extraction over a staging area so its contents can be checked before committing.
     *
     * @param uploadId the staging area
     * @return per-document diagnostics, and which lectures already exist in the corpus
     */
    public Preview preview(String uploadId) {
        Path staging = requireStaging(uploadId);
        List<DocumentReport> documents = new CorpusLoader().load(staging).reports();
        Set<String> existing = new LinkedHashSet<>();
        Path corpusRoot = Path.of(properties.corpusPath());
        for (DocumentReport report : documents) {
            String lecture = report.documentId().contains("/") ? report.documentId().substring(0, report.documentId().indexOf('/')) : "(root)";
            if (Files.isDirectory(corpusRoot.resolve(lecture))) {
                existing.add(lecture);
            }
        }
        return new Preview(uploadId, documents, List.copyOf(existing));
    }

    /**
     * Move a staging area into the corpus and drop the index so it is rebuilt.
     *
     * @param uploadId the staging area
     * @return the number of files committed
     */
    public int commit(String uploadId) {
        Path staging = requireStaging(uploadId);
        Path corpusRoot = Path.of(properties.corpusPath());
        int moved = 0;
        try {
            Files.createDirectories(corpusRoot);
            List<Path> files;
            try (var walk = Files.walk(staging)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                Path target = corpusRoot.resolve(staging.relativize(file));
                Files.createDirectories(target.getParent());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
            deleteRecursively(staging);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to commit upload " + uploadId, e);
        }
        corpus.invalidate();
        log.info("Upload {}: committed {} file(s) into {}", uploadId, moved, corpusRoot);
        return moved;
    }

    /**
     * Throw away a staging area.
     *
     * @param uploadId the staging area
     */
    public void discard(String uploadId) {
        Path staging = requireStaging(uploadId);
        try {
            deleteRecursively(staging);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to discard upload " + uploadId, e);
        }
        log.info("Upload {}: discarded", uploadId);
    }

    /**
     * Remove a lecture from the corpus, so a mistaken commit does not need filesystem access to undo.
     *
     * @param lecture directory name directly under the corpus root
     * @return the number of files removed
     */
    public int deleteLecture(String lecture) {
        Path target = safeResolve(Path.of(properties.corpusPath()), lecture).orElseThrow(() -> new IllegalArgumentException("Invalid lecture name: " + lecture));
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("No lecture '" + lecture + "' in the corpus");
        }
        int removed;
        try (var walk = Files.walk(target)) {
            removed = (int) walk.filter(Files::isRegularFile).count();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect " + target, e);
        }
        try {
            deleteRecursively(target);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + target, e);
        }
        corpus.invalidate();
        log.info("Deleted lecture '{}' ({} file(s))", lecture, removed);
        return removed;
    }

    /** @return lectures currently in the corpus, with their PDF counts */
    public List<Lecture> lectures() {
        Path corpusRoot = Path.of(properties.corpusPath());
        if (!Files.isDirectory(corpusRoot)) {
            return List.of();
        }
        try (var top = Files.list(corpusRoot)) {
            return top.filter(Files::isDirectory).sorted().map(directory -> {
                try (var walk = Files.walk(directory)) {
                    long pdfs = walk.filter(Files::isRegularFile).filter(path -> "pdf".equals(extensionOf(path.getFileName().toString()))).count();
                    return new Lecture(directory.getFileName().toString(), (int) pdfs);
                }
                catch (IOException e) {
                    return new Lecture(directory.getFileName().toString(), 0);
                }
            }).toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + corpusRoot, e);
        }
    }

    /**
     * @param name     directory name under the corpus root
     * @param pdfCount PDFs it holds
     */
    public record Lecture(String name, int pdfCount) {
    }

    private Path stagingRoot() {
        Path database = Path.of(properties.batch().databasePath());
        Path parent = database.getParent() == null ? Path.of(".") : database.getParent();
        return parent.resolve("uploads");
    }

    private Path requireStaging(String uploadId) {
        Path staging = safeResolve(stagingRoot(), uploadId).orElseThrow(() -> new IllegalArgumentException("Invalid upload id"));
        if (!Files.isDirectory(staging)) {
            throw new IllegalArgumentException("No pending upload " + uploadId);
        }
        return staging;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
