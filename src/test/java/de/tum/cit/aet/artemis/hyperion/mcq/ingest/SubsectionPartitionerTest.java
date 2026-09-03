package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;

class SubsectionPartitionerTest {

    @Test
    void partition_ordersByDocumentThenPageBeforeCutting() {
        List<Chunk> chunks = List.of(chunk("b.pdf", 5), chunk("a.pdf", 9), chunk("a.pdf", 1), chunk("b.pdf", 2));

        List<List<Chunk>> partitions = SubsectionPartitioner.partition(chunks, 2);

        assertThat(partitions).hasSize(2);
        assertThat(partitions.get(0)).extracting(Chunk::chunkId).containsExactly("a.pdf#1", "a.pdf#9");
        assertThat(partitions.get(1)).extracting(Chunk::chunkId).containsExactly("b.pdf#2", "b.pdf#5");
    }

    @Test
    void partition_spreadsARemainderAcrossTheLeadingGroups() {
        List<Chunk> chunks = List.of(chunk("a.pdf", 1), chunk("a.pdf", 2), chunk("a.pdf", 3), chunk("a.pdf", 4), chunk("a.pdf", 5));

        List<List<Chunk>> partitions = SubsectionPartitioner.partition(chunks, 3);

        assertThat(partitions).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    void partition_coversEveryChunkExactlyOnce() {
        List<Chunk> chunks = List.of(chunk("a.pdf", 1), chunk("a.pdf", 2), chunk("b.pdf", 1), chunk("b.pdf", 2), chunk("c.pdf", 1));

        List<List<Chunk>> partitions = SubsectionPartitioner.partition(chunks, 4);

        assertThat(partitions.stream().flatMap(List::stream).map(Chunk::chunkId)).containsExactlyInAnyOrder("a.pdf#1", "a.pdf#2", "b.pdf#1", "b.pdf#2", "c.pdf#1");
    }

    @Test
    void partition_returnsOneGroupPerChunkWhenFewerChunksThanRequested() {
        List<Chunk> chunks = List.of(chunk("a.pdf", 1), chunk("a.pdf", 2));

        List<List<Chunk>> partitions = SubsectionPartitioner.partition(chunks, 5);

        assertThat(partitions).hasSize(2);
        assertThat(partitions).allSatisfy(group -> assertThat(group).hasSize(1));
    }

    @Test
    void partition_rejectsZeroSubsectionsAndEmptyInput() {
        assertThatThrownBy(() -> SubsectionPartitioner.partition(List.of(chunk("a.pdf", 1)), 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0");
        assertThatThrownBy(() -> SubsectionPartitioner.partition(List.of(), 3)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero chunks");
    }

    private static Chunk chunk(String document, int page) {
        return new Chunk(document + "#" + page, document, "Lecture", document, page, page, SourceRole.LECTURE_DECK, "text");
    }
}
