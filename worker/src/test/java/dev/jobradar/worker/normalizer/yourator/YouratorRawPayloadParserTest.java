package dev.jobradar.worker.normalizer.yourator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.worker.normalizer.NormalizedJob;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class YouratorRawPayloadParserTest {

    private final YouratorRawPayloadParser parser = new YouratorRawPayloadParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesStructuredSalaryFromJobPostingJsonLd() throws Exception {
        JsonNode payload = objectMapper.readTree(fixture("yourator-job-posting.json"));

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.title()).isEqualTo("Lead Software Engineer");
        assertThat(normalized.company()).isEqualTo("AIFIAN");
        assertThat(normalized.salaryMin()).isEqualTo(1_800_000L);
        assertThat(normalized.salaryMax()).isEqualTo(2_500_000L);
        assertThat(normalized.salaryCurrency()).isEqualTo("TWD");
        assertThat(normalized.description()).contains("Lead the technical strategy");
    }

    @Test
    void handlesMissingBaseSalaryAsNull() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"title": "Negotiable Role", "hiringOrganization": {"name": "Acme"}, "description": "..."}
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.salaryMin()).isNull();
        assertThat(normalized.salaryMax()).isNull();
        assertThat(normalized.salaryCurrency()).isNull();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
