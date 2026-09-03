package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;

/**
 * Splits a competency's retrieved material into contiguous subsections for grounding.
 * <p>
 * Chunks are ordered by document and first page, then cut into groups of near-equal size, so each group is
 * a contiguous slice of the material as a reader would encounter it. Grounding one generated item per
 * subsection spreads a cell's items across all of the competency's material instead of whichever pages
 * retrieval ranked highest.
 */
public final class SubsectionPartitioner {

    private SubsectionPartitioner() {
    }

    /**
     * Partitions retrieved snippets into at most {@code subsections} contiguous groups, ordered by the
     * document and first page encoded in each snippet's chunk id ({@code documentId#pFirst-pLast}).
     *
     * @param snippets    the retrieved snippets, in any order
     * @param subsections groups to aim for; when fewer snippets exist, each snippet becomes its own group
     * @return non-empty groups in reading order, together covering exactly the given snippets
     * @throws IllegalArgumentException if {@code subsections} is below 1 or {@code snippets} is empty
     */
    public static List<List<Snippet>> partitionSnippets(List<Snippet> snippets, int subsections) {
        if (subsections < 1) {
            throw new IllegalArgumentException("Require at least 1 subsection, got " + subsections);
        }
        if (snippets.isEmpty()) {
            throw new IllegalArgumentException("Cannot partition zero snippets");
        }
        List<Snippet> ordered = snippets.stream()
                .sorted(Comparator.comparing((Snippet snippet) -> documentOf(snippet.chunkId())).thenComparingInt(snippet -> firstPageOf(snippet.chunkId()))).toList();
        return cut(ordered, subsections);
    }

    private static String documentOf(String chunkId) {
        int hash = chunkId.lastIndexOf('#');
        return hash < 0 ? chunkId : chunkId.substring(0, hash);
    }

    private static int firstPageOf(String chunkId) {
        int hash = chunkId.lastIndexOf('#');
        if (hash < 0 || hash == chunkId.length() - 1) {
            return 0;
        }
        String range = chunkId.substring(hash + 1);
        int dash = range.indexOf('-');
        try {
            return Integer.parseInt(dash < 0 ? range : range.substring(0, dash).replace("p", ""));
        }
        catch (NumberFormatException _) {
            return 0;
        }
    }

    /**
     * Partitions chunks into at most {@code subsections} contiguous groups.
     *
     * @param chunks      the retrieved chunks, in any order
     * @param subsections groups to aim for; when fewer chunks exist, each chunk becomes its own group
     * @return non-empty groups in reading order, together covering exactly the given chunks
     * @throws IllegalArgumentException if {@code subsections} is below 1 or {@code chunks} is empty
     */
    public static List<List<Chunk>> partition(List<Chunk> chunks, int subsections) {
        if (subsections < 1) {
            throw new IllegalArgumentException("Require at least 1 subsection, got " + subsections);
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Cannot partition zero chunks");
        }

        List<Chunk> ordered = chunks.stream().sorted(Comparator.comparing(Chunk::documentId).thenComparingInt(Chunk::firstPage)).toList();
        return cut(ordered, subsections);
    }

    private static <T> List<List<T>> cut(List<T> ordered, int subsections) {
        int groups = Math.min(subsections, ordered.size());
        int base = ordered.size() / groups;
        int remainder = ordered.size() % groups;

        List<List<T>> partitions = new ArrayList<>(groups);
        int position = 0;
        for (int group = 0; group < groups; group++) {
            int size = base + (group < remainder ? 1 : 0);
            partitions.add(List.copyOf(ordered.subList(position, position + size)));
            position += size;
        }
        return List.copyOf(partitions);
    }
}
