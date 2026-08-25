package de.tum.cit.aet.artemis.hyperion.mcq.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.readiness.Readiness.Check;
import de.tum.cit.aet.artemis.hyperion.mcq.readiness.Readiness.Status;

class ReadinessServiceTest {

    @Test
    void treatsATaggedProviderIdAsTheSameModelAsTheUntaggedNameWeConfigure() {
        // Ollama reports nomic-embed-text:latest while requests using the untagged name resolve to it.
        assertThat(ReadinessService.sameModel("nomic-embed-text:latest", "nomic-embed-text")).isTrue();
        assertThat(ReadinessService.sameModel("nomic-embed-text", "nomic-embed-text:latest")).isTrue();
        assertThat(ReadinessService.sameModel("nomic-embed-text", "nomic-embed-text")).isTrue();
    }

    @Test
    void doesNotConflateDifferentModelsThatSharePrefixText() {
        assertThat(ReadinessService.sameModel("gpt-oss-120b", "gpt-oss-20b")).isFalse();
        assertThat(ReadinessService.sameModel("nomic-embed-text-v2", "nomic-embed-text")).isFalse();
        assertThat(ReadinessService.sameModel("openai/gpt-oss-120b", "gpt-oss-120b")).isFalse();
    }

    @Test
    void isReadyOnlyWhenNothingIsBlocked() {
        assertThat(new Readiness(List.of(Check.ok("a", "fine"), Check.warn("b", "meh", "optional"))).ready()).isTrue();
        assertThat(new Readiness(List.of(Check.ok("a", "fine"), Check.blocked("b", "broken", "fix it"))).ready()).isFalse();
    }

    @Test
    void separatesBlockersFromWarningsSoTheUserKnowsWhatMustBeFixed() {
        Readiness readiness = new Readiness(List.of(Check.ok("a", "fine"), Check.warn("b", "meh", "optional"), Check.blocked("c", "broken", "fix it")));

        assertThat(readiness.blockers()).extracting(Check::name).containsExactly("c");
        assertThat(readiness.warnings()).extracting(Check::name).containsExactly("b");
    }

    @Test
    void anOptionalBackendFailingWarnsWhileArequiredOneBlocks() {
        // A half-configured cloud backend must not make the tool refuse to run: nothing depends on it
        // until a run plan names one of its models.
        assertThat(ReadinessService.verdict(false, "Cloud model (azure)", "rejected the key", "Check it.").status()).isEqualTo(Status.WARN);
        assertThat(ReadinessService.verdict(true, "Local model", "rejected the key", "Check it.").status()).isEqualTo(Status.BLOCKED);
        assertThat(new Readiness(List.of(ReadinessService.verdict(false, "Cloud model (azure)", "down", "Check it."))).ready()).isTrue();
    }

    @Test
    void everyBlockerCarriesAFixBecauseAVerdictAloneIsNotActionable() {
        Check blocked = Check.blocked("Embedding backend", "unreachable", "Start it: ollama serve");

        assertThat(blocked.status()).isEqualTo(Status.BLOCKED);
        assertThat(blocked.fix()).isNotBlank();
    }
}
