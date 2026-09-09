package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import java.util.Locale;
import java.util.Optional;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;

/**
 * Resolves and renders a request's competencies for prompts.
 */
final class Competencies {

    private Competencies() {
    }

    /**
     * Renders every requested competency as a titled block of learning objectives.
     *
     * @param request the request naming the competencies
     * @param manifest the course model declaring them
     * @return the rendered block
     * @throws IllegalArgumentException if the request names a competency the course model does not declare
     */
    static String render(GenerationRequest request, CompetencyManifest manifest) {
        StringBuilder rendered = new StringBuilder();
        for (String key : request.competencyKeys()) {
            Competency competency = resolve(request, manifest, key);
            rendered.append(competency.title()).append(" (").append(competency.taxonomy()).append(")\n");
            if (competency.description() != null && !competency.description().isBlank()) {
                rendered.append(competency.description()).append('\n');
            }
            rendered.append('\n');
        }
        return rendered.toString().strip();
    }

    /**
     * Resolves the competency a generated question declares to one of the request's competency keys.
     * <p>
     * A single-competency request needs no declaration: every question targets the one competency asked
     * for. Otherwise the declaration is matched, ignoring case and surrounding whitespace, against each
     * requested competency's key and title. An unmatched declaration yields an empty result, because a
     * question whose targeted competency is unknown cannot be scored against a learning objective.
     *
     * @param request  the request naming the candidate competencies
     * @param manifest the course model declaring them
     * @param declared the competency title or key the model named, possibly {@code null}
     * @return the resolved competency key, or empty when it cannot be resolved
     */
    static Optional<String> match(GenerationRequest request, CompetencyManifest manifest, String declared) {
        if (request.competencyKeys().size() == 1) {
            return Optional.of(request.competencyKeys().getFirst());
        }
        if (declared == null || declared.isBlank()) {
            return Optional.empty();
        }
        String needle = declared.strip().toLowerCase(Locale.ROOT);
        for (String key : request.competencyKeys()) {
            Competency competency = resolve(request, manifest, key);
            if (needle.equals(key.toLowerCase(Locale.ROOT)) || needle.equals(competency.title().strip().toLowerCase(Locale.ROOT))) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves one competency key against the course model.
     *
     * @param request  the request naming it, used in the error message
     * @param manifest the course model
     * @param key      the competency key
     * @return the competency
     * @throws IllegalArgumentException if the course model does not declare it
     */
    static Competency resolve(GenerationRequest request, CompetencyManifest manifest, String key) {
        return manifest.byKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Request '" + request.key() + "' names competency '" + key + "', which the course model does not declare"));
    }
}
