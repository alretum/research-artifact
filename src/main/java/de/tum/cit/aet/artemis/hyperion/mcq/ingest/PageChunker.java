package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Page;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;

/**
 * Groups consecutive pages of a document into chunks of roughly {@code targetTokens}.
 * <p>
 * A chunk never spans two documents, and always covers a contiguous page range. A page whose own
 * length reaches {@code maxTokens} becomes a chunk on its own.
 */
public class PageChunker {

    private final int targetTokens;

    private final int maxTokens;

    /**
     * @param targetTokens accumulate pages until this estimated token count is reached
     * @param maxTokens    a single page at or above this size becomes its own chunk
     * @throws IllegalArgumentException if {@code targetTokens} is not positive or exceeds {@code maxTokens}
     */
    public PageChunker(int targetTokens, int maxTokens) {
        if (targetTokens <= 0 || targetTokens > maxTokens) {
            throw new IllegalArgumentException("Require 0 < targetTokens <= maxTokens, got " + targetTokens + " and " + maxTokens);
        }
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
    }

    /**
     * Group pages into chunks.
     *
     * @param pages   pages in document and page order
     * @param roleFor resolves a document id to its source role
     * @return chunks in input order
     */
    public List<Chunk> chunk(List<Page> pages, Function<String, SourceRole> roleFor) {
        List<Chunk> chunks = new ArrayList<>();
        List<Page> pending = new ArrayList<>();
        int pendingTokens = 0;

        for (Page page : pages) {
            if (!pending.isEmpty() && !pending.getFirst().documentId().equals(page.documentId())) {
                flush(chunks, pending, roleFor);
                pending = new ArrayList<>();
                pendingTokens = 0;
            }

            int pageTokens = CorpusLoader.approxTokens(page.text());
            if (pageTokens >= maxTokens) {
                flush(chunks, pending, roleFor);
                pending = new ArrayList<>();
                pendingTokens = 0;
                chunks.add(toChunk(List.of(page), roleFor));
                continue;
            }

            pending.add(page);
            pendingTokens += pageTokens;
            if (pendingTokens >= targetTokens) {
                flush(chunks, pending, roleFor);
                pending = new ArrayList<>();
                pendingTokens = 0;
            }
        }
        flush(chunks, pending, roleFor);
        return chunks;
    }

    private static void flush(List<Chunk> chunks, List<Page> pending, Function<String, SourceRole> roleFor) {
        if (!pending.isEmpty()) {
            chunks.add(toChunk(List.copyOf(pending), roleFor));
        }
    }

    private static Chunk toChunk(List<Page> pages, Function<String, SourceRole> roleFor) {
        Page first = pages.getFirst();
        Page last = pages.getLast();
        String text = String.join("\n\n", pages.stream().map(Page::text).toList());
        String chunkId = first.documentId() + "#p" + first.pageNumber() + "-" + last.pageNumber();
        return new Chunk(chunkId, first.documentId(), first.lectureName(), first.unitName(), first.pageNumber(), last.pageNumber(), roleFor.apply(first.documentId()), text);
    }
}
