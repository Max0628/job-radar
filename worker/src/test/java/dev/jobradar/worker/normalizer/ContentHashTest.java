package dev.jobradar.worker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentHashTest {

    @Test
    void sameInputProducesSameHash() {
        NormalizedJob job = new NormalizedJob("Title", "Company", 100L, 200L, "TWD", "desc");

        assertThat(ContentHash.of(job)).isEqualTo(ContentHash.of(job));
    }

    @Test
    void differentSalaryProducesDifferentHash() {
        NormalizedJob original = new NormalizedJob("Title", "Company", 100L, 200L, "TWD", "desc");
        NormalizedJob changed = new NormalizedJob("Title", "Company", 100L, 300L, "TWD", "desc");

        assertThat(ContentHash.of(original)).isNotEqualTo(ContentHash.of(changed));
    }

    @Test
    void nullFieldsDoNotThrow() {
        NormalizedJob job = new NormalizedJob(null, null, null, null, null, null);

        assertThat(ContentHash.of(job)).isNotBlank();
    }
}
