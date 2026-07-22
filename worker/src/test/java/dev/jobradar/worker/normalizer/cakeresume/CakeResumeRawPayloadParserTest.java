package dev.jobradar.worker.normalizer.cakeresume;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.worker.normalizer.NormalizedJob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CakeResumeRawPayloadParserTest {

    private final CakeResumeRawPayloadParser parser = new CakeResumeRawPayloadParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesStructuredSalaryAndDashboardFields() throws Exception {
        JsonNode payload = objectMapper.readTree(fixture("cakeresume-job-with-salary.json"));

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.title()).isEqualTo("Backend Engineer / 後端工程師");
        assertThat(normalized.company()).isEqualTo("SWAG");
        assertThat(normalized.salaryMin()).isEqualTo(1_250_000L);
        assertThat(normalized.salaryMax()).isEqualTo(2_000_000L);
        assertThat(normalized.salaryCurrency()).isEqualTo("TWD");
        assertThat(normalized.jobType()).isEqualTo("full_time");
        assertThat(normalized.seniorityLevel()).isEqualTo("mid_senior_level");
        assertThat(normalized.langName()).isEqualTo("English");
        assertThat(normalized.minWorkExpYear()).isEqualTo(3);
        assertThat(normalized.numberOfOpenings()).isEqualTo(1);
        // CakeResume 沒有 employmentType 概念（用 jobType 表達），維持 null
        assertThat(normalized.employmentType()).isNull();
        // fixture 的 locations 是 ["台北市, 台灣"]（2 段：市, 國，無區級資訊）
        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isNull();
        assertThat(normalized.postedAt()).isEqualTo(Instant.parse("2026-07-20T02:02:23.401928Z"));
    }

    @Test
    void handlesNullSalaryAsNegotiable() throws Exception {
        JsonNode payload = objectMapper.readTree(fixture("cakeresume-job-negotiable-salary.json"));

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.title()).isEqualTo("【工程部】後端工程師 Backend Engineer");
        assertThat(normalized.company()).isEqualTo("CMoney全曜財經資訊股份有限公司");
        assertThat(normalized.salaryMin()).isNull();
        assertThat(normalized.salaryMax()).isNull();
        assertThat(normalized.numberOfOpenings()).isEqualTo(3);
        // fixture 的 locations 是 ["板橋區, 新北市, 台灣"]（3 段：區, 市, 國）
        assertThat(normalized.city()).isEqualTo("新北市");
        assertThat(normalized.district()).isEqualTo("板橋區");
        assertThat(normalized.postedAt()).isEqualTo(Instant.parse("2026-07-20T07:18:33.164382Z"));
    }

    @Test
    void returnsNullPostedAtWhenContentUpdatedAtIsUnparseable() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"title": "Remote Role", "page": {"name": "Acme"}, "content_updated_at": "not-a-real-date"}
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.postedAt()).isNull();
    }

    @Test
    void leavesLocationNullWhenLocationsArrayIsEmpty() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"title": "Remote Role", "page": {"name": "Acme"}, "locations": []}
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isNull();
        assertThat(normalized.district()).isNull();
    }

    @Test
    void leavesLocationNullWhenLocationsFieldIsMissing() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"title": "Remote Role", "page": {"name": "Acme"}}
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isNull();
        assertThat(normalized.district()).isNull();
    }

    @Test
    void leavesLocationNullWhenSingleSegmentCountryOnly() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"title": "Remote Role", "page": {"name": "Acme"}, "locations": ["Taiwan"]}
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isNull();
        assertThat(normalized.district()).isNull();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
