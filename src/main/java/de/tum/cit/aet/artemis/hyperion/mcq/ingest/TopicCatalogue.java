package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;

/**
 * The set of topics a corpus can be queried for, derived from its folder structure.
 * <p>
 * A topic corresponds to one top-level corpus folder. Folders carrying no chunks are reported as
 * ungrounded rather than silently omitted, because a topic with no material of its own would otherwise
 * be answered from whatever unrelated chunks happen to rank highest.
 */
public final class TopicCatalogue {

    /**
     * One topic derived from a corpus folder.
     *
     * @param key       the folder name as it appears in the corpus
     * @param query     the folder name with any leading ordinal removed, used as the retrieval query
     * @param chunkCount number of indexed chunks belonging to this topic
     */
    public record Topic(String key, String query, int chunkCount) {

        public boolean grounded() {
            return chunkCount > 0;
        }
    }

    private TopicCatalogue() {
    }

    /**
     * Derive topics from indexed chunks.
     *
     * @param chunks all chunks of the corpus
     * @return topics in corpus order, each with its chunk count
     */
    public static List<Topic> fromChunks(List<Chunk> chunks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            counts.merge(chunk.lectureName(), 1, Integer::sum);
        }
        List<Topic> topics = new ArrayList<>();
        counts.forEach((key, count) -> topics.add(new Topic(key, toQuery(key), count)));
        return List.copyOf(topics);
    }

    /**
     * Derive topics from a competency manifest, using each competency's retrieval query.
     *
     * @param manifest declared competencies
     * @param chunks   all chunks of the corpus, used to count material per competency
     * @return topics in manifest order
     */
    public static List<Topic> fromManifest(CompetencyManifest manifest, List<Chunk> chunks) {
        Map<String, Integer> chunksByDocument = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            chunksByDocument.merge(chunk.documentId(), 1, Integer::sum);
        }
        return manifest.competencies().stream().map(competency -> new Topic(competency.title(), competency.retrievalQuery(), countLinked(competency, chunksByDocument))).toList();
    }

    private static int countLinked(Competency competency, Map<String, Integer> chunksByDocument) {
        return competency.linkedDocuments().stream().mapToInt(document -> chunksByDocument.getOrDefault(document, 0)).sum();
    }

    /**
     * Read topics from a file, one per line, ignoring blank lines and lines starting with {@code #}.
     *
     * @param file file to read
     * @return topics in file order, each reported with a zero chunk count
     * @throws UncheckedIOException if the file cannot be read
     */
    public static List<Topic> fromFile(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> new Topic(line, line, 0)).toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read topics from " + file, e);
        }
    }

    /**
     * Strip a leading ordinal such as {@code "05 "} so the remainder reads as a natural query.
     *
     * @param folderName corpus folder name
     * @return the query form of the folder name
     */
    static String toQuery(String folderName) {
        return folderName.replaceFirst("^\\d+\\s+", "").replaceAll("_{2,}", " ").replaceAll("\\s{2,}", " ").strip();
    }
}
