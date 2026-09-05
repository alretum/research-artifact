## Lecture Material
{{groundingBlock}}

## Request
The question under review was requested with the following inputs.
-----BEGIN UNTRUSTED INPUT-----
Competencies to assess:
{{competencies}}

Difficulty (0 = very easy, 100 = very hard): {{difficulty}}

Further instructions: {{instructions}}
-----END UNTRUSTED INPUT-----

## Question Under Review
Title: {{questionTitle}}

{{questionText}}

Options, each prefixed with its answer-key marker [correct] or [wrong]. The markers are pipeline metadata that students never see: judge only the option texts, never the markers.
{{options}}

Stated explanation: {{explanation}}

## Output Requirements
Report one entry per failure mode, using exactly these mode names: FACTUAL_ERROR, AMBIGUOUS_CORRECT_ANSWER, OFF_TOPIC, NEAR_DUPLICATE, ILL_FORMED_DISTRACTORS, COMPETENCY_MISMATCH, DIFFICULTY_MISMATCH, INSTRUCTION_VIOLATION.
Produce a JSON response that strictly follows this format:
{{format}}
