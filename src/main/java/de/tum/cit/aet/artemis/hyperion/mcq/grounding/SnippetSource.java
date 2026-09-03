package de.tum.cit.aet.artemis.hyperion.mcq.grounding;

import java.util.List;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;

/**
 * Supplies the lecture-material snippets most relevant to a query.
 */
public interface SnippetSource {

    /**
     * Return at most {@code limit} snippets relevant to {@code query}.
     *
     * @param query     free-text search query
     * @param limit     maximum number of snippets to return, highest relevance first
     * @param courseKey course whose material to search, or {@code null} for all indexed material
     * @return snippets ordered by descending relevance, empty if nothing matches
     */
    List<Snippet> search(String query, int limit, String courseKey);
}
