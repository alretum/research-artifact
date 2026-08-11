package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Page;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;

class PageChunkerTest {

    private static final int TARGET = 100;

    private static final int MAX = 400;

    private final PageChunker chunker = new PageChunker(TARGET, MAX);

    @Test
    void keepsAPageAloneWhenItAlreadyMeetsTheTarget() {
        List<Chunk> chunks = chunker.chunk(pages("doc.pdf", 4, 300), role());

        assertThat(chunks).hasSize(4);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.firstPage()).isEqualTo(chunk.lastPage()));
    }

    @Test
    void mergesSeveralShortPagesIntoOneChunk() {
        List<Chunk> chunks = chunker.chunk(pages("doc.pdf", 6, 100), role());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().firstPage()).isEqualTo(1);
        assertThat(chunks.getFirst().lastPage()).isEqualTo(3);
        assertThat(chunks.getLast().firstPage()).isEqualTo(4);
    }

    @Test
    void neverSpansTwoDocuments() {
        List<Page> pages = new java.util.ArrayList<>(pages("first.pdf", 1, 40));
        pages.addAll(pages("second.pdf", 1, 40));

        List<Chunk> chunks = chunker.chunk(pages, role());

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(Chunk::documentId).containsExactly("first.pdf", "second.pdf");
    }

    @Test
    void isolatesAPageAtOrAboveTheMaximum() {
        List<Page> pages = List.of(page("doc.pdf", 1, 40), page("doc.pdf", 2, MAX * 4), page("doc.pdf", 3, 40));

        List<Chunk> chunks = chunker.chunk(pages, role());

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1).firstPage()).isEqualTo(2);
        assertThat(chunks.get(1).lastPage()).isEqualTo(2);
    }

    @Test
    void carriesTheSourceRoleThroughToTheChunk() {
        List<Chunk> chunks = chunker.chunk(pages("solution.pdf", 1, 40), documentId -> SourceRole.SOLUTION);

        assertThat(chunks.getFirst().role()).isEqualTo(SourceRole.SOLUTION);
    }

    @Test
    void rendersAPageRangeInTheHeader() {
        List<Chunk> single = chunker.chunk(pages("doc.pdf", 1, 40), role());
        List<Chunk> merged = chunker.chunk(pages("doc.pdf", 3, 100), role());

        assertThat(single.getFirst().pageRange()).isEqualTo("Page 1");
        assertThat(merged.getFirst().pageRange()).isEqualTo("Pages 1–3");
        assertThat(merged.getFirst().header()).contains("Lecture: lecture", "Unit: doc", "Pages 1–3");
    }

    @Test
    void returnsNoChunksForNoPages() {
        assertThat(chunker.chunk(List.of(), role())).isEmpty();
    }

    @Test
    void rejectsAnInvalidTokenBudget() {
        assertThatThrownBy(() -> new PageChunker(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageChunker(500, 100)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetTokens");
    }

    private static java.util.function.Function<String, SourceRole> role() {
        return documentId -> SourceRole.LECTURE_DECK;
    }

    private static List<Page> pages(String documentId, int count, int charsPerPage) {
        return java.util.stream.IntStream.rangeClosed(1, count).mapToObj(number -> page(documentId, number, charsPerPage)).toList();
    }

    private static Page page(String documentId, int number, int chars) {
        return new Page(documentId, "lecture", documentId.replace(".pdf", ""), number, "x".repeat(chars));
    }
}
