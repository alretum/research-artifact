package de.tum.cit.aet.artemis.hyperion.mcq.grounding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingComposition;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader;

/**
 * Turns retrieved snippets into the grounding block inserted into a prompt.
 * <p>
 * Snippets are taken in relevance order until the token budget is reached. The leading snippet is always
 * included, even when it alone exceeds the budget, so grounding is never empty; the assembled block can
 * therefore exceed {@code maxTokens} in that case. The block is wrapped in untrusted-input markers so
 * instructions appearing inside course material are not followed.
 */
@Service
public class GroundingAssemblyService {

    private static final String BEGIN_MARKER = "-----BEGIN UNTRUSTED INPUT-----";

    private static final String END_MARKER = "-----END UNTRUSTED INPUT-----";

    /**
     * Assemble a grounding context.
     *
     * @param topic         the topic the snippets were retrieved for
     * @param snippets      candidate snippets, highest relevance first
     * @param maxTokens     upper bound on the estimated size of the rendered block
     * @return the assembled context; {@code snippets} is the subset that fitted the budget
     * @throws IllegalArgumentException if {@code maxTokens} is not positive
     */
    public GroundingContext assemble(String topic, List<Snippet> snippets, int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive, got " + maxTokens);
        }

        List<Snippet> included = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int tokens = 0;

        for (Snippet snippet : snippets) {
            String rendered = render(snippet);
            int renderedTokens = CorpusLoader.approxTokens(rendered);

            if (!included.isEmpty() && tokens + renderedTokens > maxTokens) {
                break;
            }

            body.append(rendered).append("\n\n");
            tokens += renderedTokens;
            included.add(snippet);
        }

        String block = BEGIN_MARKER + "\n" + body.toString().strip() + "\n" + END_MARKER;
        List<Snippet> includedSnippets = List.copyOf(included);
        return new GroundingContext(topic, includedSnippets, block, CorpusLoader.approxTokens(block), GroundingComposition.of(includedSnippets));
    }

    private static String render(Snippet snippet) {
        String header = "Lecture: " + snippet.source() + ", Unit: " + snippet.unit();
        if (snippet.pageRange() != null && !snippet.pageRange().isBlank()) {
            header = header + ", " + snippet.pageRange();
        }
        return header + "\n" + snippet.text();
    }
}
