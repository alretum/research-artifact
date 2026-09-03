package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;

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
        int groups = Math.min(subsections, ordered.size());
        int base = ordered.size() / groups;
        int remainder = ordered.size() % groups;

        List<List<Chunk>> partitions = new ArrayList<>(groups);
        int position = 0;
        for (int group = 0; group < groups; group++) {
            int size = base + (group < remainder ? 1 : 0);
            partitions.add(List.copyOf(ordered.subList(position, position + size)));
            position += size;
        }
        return List.copyOf(partitions);
    }
}
