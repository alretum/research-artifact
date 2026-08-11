package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Page;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;

/**
 * Extracts text from every PDF under a corpus directory, one page at a time.
 * <p>
 * Extracted text is not modified in any way. Pages yielding fewer than
 * {@value #MIN_PAGE_CHARS} characters are treated as text-poor and excluded from the returned pages,
 * but are counted in the {@link DocumentReport}. Pages whose text has already been seen verbatim
 * elsewhere in the corpus are skipped, which removes boilerplate such as a repeated course-outline page
 * before it can be merged into a chunk.
 */
public class CorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

    private static final int MIN_PAGE_CHARS = 40;

    /**
     * Tokens that are real words only once a dropped {@code fi}/{@code fl} ligature is restored, and
     * that are implausible as written. Used to quantify extraction damage.
     */
    private static final Pattern LIGATURE_DAMAGE = Pattern.compile("\\b(rst|nal|nally|ctitious|nite|nitely|dened|coe cient|coe cients|su cient|"
            + "signi cant|signi cantly|speci c|speci cally|classi cation|identi ed|simpli ed|modi ed|satis ed|con guration|pro t|bene t)\\b");

    /**
     * Screen-reader alternative text embedded in the PDFs, which extracts as body text. Counted so the
     * report reflects it; the lines themselves are left in place.
     */
    private static final Pattern ALT_TEXT = Pattern.compile("(?i)\\b(superscript base|end base|to the cap|asterisk operator|subscript base|end subscript)\\b");

    private static final List<String> GERMAN_MARKERS = List.of("der", "die", "das", "und", "nicht", "eine", "für", "wird", "sind");

    private static final List<String> ENGLISH_MARKERS = List.of("the", "and", "of", "is", "for", "with", "that", "are", "this");

    /** Per-document extraction diagnostics. Not consumed by the pipeline. */
    public record DocumentReport(String documentId, SourceRole role, int pages, int textPoorPages, int chars, int approxTokens, int suspectedDamagedTokens, int altTextLines,
            String detectedLanguage) {
    }

    /** Extracted pages across all documents, with one report per document. */
    public record LoadResult(List<Page> pages, List<DocumentReport> reports) {

        public int totalApproxTokens() {
            return reports.stream().mapToInt(DocumentReport::approxTokens).sum();
        }
    }

    /**
     * Extract every PDF found beneath {@code corpusRoot}, recursively.
     * <p>
     * The first path element below the root is used as the lecture name, the filename as the unit name.
     * Unreadable PDFs are logged and skipped rather than failing the load.
     *
     * @param corpusRoot directory to walk
     * @return extracted pages and per-document reports, ordered by path
     * @throws IllegalArgumentException if {@code corpusRoot} is not a directory
     * @throws IllegalStateException    if the directory cannot be walked
     */
    public LoadResult load(Path corpusRoot) {
        if (!Files.isDirectory(corpusRoot)) {
            throw new IllegalArgumentException("Corpus directory not found: " + corpusRoot.toAbsolutePath());
        }

        List<Path> pdfs;
        try (Stream<Path> walk = Files.walk(corpusRoot)) {
            pdfs = walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to walk corpus at " + corpusRoot, e);
        }

        List<Page> pages = new ArrayList<>();
        List<DocumentReport> reports = new ArrayList<>();
        Set<String> seenPageTexts = new HashSet<>();
        for (Path pdf : pdfs) {
            extract(corpusRoot, pdf, seenPageTexts).ifPresent(extracted -> {
                pages.addAll(extracted.pages());
                reports.add(extracted.report());
            });
        }

        log.info("Loaded {} PDFs, {} usable pages, ~{} tokens", reports.size(), pages.size(), reports.stream().mapToInt(DocumentReport::approxTokens).sum());
        return new LoadResult(pages, reports);
    }

    private record Extracted(List<Page> pages, DocumentReport report) {
    }

    private Optional<Extracted> extract(Path corpusRoot, Path pdf, Set<String> seenPageTexts) {
        String documentId = corpusRoot.relativize(pdf).toString();
        String lectureName = documentId.contains("/") ? documentId.substring(0, documentId.indexOf('/')) : "(root)";
        String unitName = pdf.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");

        List<Page> pages = new ArrayList<>();
        int textPoorPages = 0;
        int chars = 0;
        int damagedTokens = 0;
        int altTextLines = 0;
        int duplicatePages = 0;

        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                String text = extractPage(document, pageNumber);
                if (text.length() < MIN_PAGE_CHARS) {
                    textPoorPages++;
                    continue;
                }
                if (!seenPageTexts.add(text)) {
                    duplicatePages++;
                    continue;
                }
                chars += text.length();
                damagedTokens += (int) LIGATURE_DAMAGE.matcher(text).results().count();
                altTextLines += (int) ALT_TEXT.matcher(text).results().count();
                pages.add(new Page(documentId, lectureName, unitName, pageNumber, text));
            }
        }
        catch (IOException e) {
            log.warn("Skipping unreadable PDF {}: {}", documentId, e.getMessage());
            return Optional.empty();
        }

        if (duplicatePages > 0) {
            log.info("Skipped {} pages of {} already seen verbatim elsewhere in the corpus", duplicatePages, documentId);
        }
        DocumentReport report = new DocumentReport(documentId, inferRole(unitName), pages.size(), textPoorPages, chars, approxTokens(chars), damagedTokens, altTextLines,
                detectLanguage(pages));
        return Optional.of(new Extracted(pages, report));
    }

    private static String extractPage(PDDocument document, int pageNumber) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document).strip();
    }

    /**
     * Classify a document by its filename.
     *
     * @param unitName filename without extension
     * @return the inferred role, {@link SourceRole#OTHER} when no pattern matches
     */
    static SourceRole inferRole(String unitName) {
        String name = unitName.toLowerCase(Locale.ROOT);
        if (name.contains("solution") || name.contains("loesung") || name.contains("lösung")) {
            return SourceRole.SOLUTION;
        }
        if (name.startsWith("ce ") || name.contains("central exercise") || name.contains("demoaufgaben")) {
            return SourceRole.CENTRAL_EXERCISE;
        }
        if (name.contains("tutorial") || name.contains("uebungsaufgaben") || name.contains("übungsaufgaben")) {
            return SourceRole.TUTORIAL;
        }
        if (name.matches("^\\d+[ _].*")) {
            return SourceRole.LECTURE_DECK;
        }
        return SourceRole.OTHER;
    }

    /**
     * Guess the dominant language from stopword frequency in the first pages.
     *
     * @param pages pages of a single document
     * @return {@code "de"}, {@code "en"}, or {@code "unknown"} when no marker is found
     */
    static String detectLanguage(List<Page> pages) {
        String sample = pages.stream().limit(5).map(Page::text).reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        int german = countMarkers(sample, GERMAN_MARKERS);
        int english = countMarkers(sample, ENGLISH_MARKERS);
        if (german == 0 && english == 0) {
            return "unknown";
        }
        return german > english ? "de" : "en";
    }

    private static int countMarkers(String text, List<String> markers) {
        return markers.stream().mapToInt(marker -> (int) Pattern.compile("\\b" + Pattern.quote(marker) + "\\b").matcher(text).results().count()).sum();
    }

    /**
     * Characters per token, measured on this corpus by comparing prompt length against the token counts
     * the model reported. Mathematical Unicode tokenises far below the usual four characters per token.
     */
    public static final double CHARS_PER_TOKEN = 2.38;

    /**
     * Estimate the token count of a string.
     *
     * @param text text to measure, may be {@code null}
     * @return estimated token count, {@code 0} for {@code null}
     */
    public static int approxTokens(String text) {
        return text == null ? 0 : approxTokens(text.length());
    }

    /**
     * Estimate the token count of a string of the given length.
     *
     * @param characterCount number of characters
     * @return estimated token count
     */
    public static int approxTokens(int characterCount) {
        return (int) Math.ceil(characterCount / CHARS_PER_TOKEN);
    }

    /**
     * Report whether the text contains tokens matching the known ligature-damage patterns.
     *
     * @param text text to inspect, may be {@code null}
     * @return {@code true} if at least one damaged token is present
     */
    public static boolean looksDamaged(String text) {
        return text != null && LIGATURE_DAMAGE.matcher(text).find();
    }
}
