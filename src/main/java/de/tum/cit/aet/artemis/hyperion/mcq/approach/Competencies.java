package de.tum.cit.aet.artemis.hyperion.mcq.approach;

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
