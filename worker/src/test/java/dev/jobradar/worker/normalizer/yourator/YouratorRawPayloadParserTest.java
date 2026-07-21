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
        // fixture 的 streetAddress 是「內湖區瑞光路335號」、addressLocality 是「臺北市」
        // （見端到端驗證抓到的真實資料，臺 要正規化成 台）
        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isEqualTo("內湖區");
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
        assertThat(normalized.city()).isNull();
        assertThat(normalized.district()).isNull();
    }

    @Test
    void extractsDistrictWhenCityNamePrecedesItInStreetAddress() throws Exception {
        // 真實資料：區不在字串最前面，前面還有縣市名稱（見端到端驗證抓到的真實職缺）
        JsonNode payload = objectMapper.readTree("""
                {
                  "title": "Sr. Backend Engineer",
                  "hiringOrganization": {"name": "TutorABC"},
                  "description": "...",
                  "jobLocation": {
                    "address": {
                      "streetAddress": "臺北市中正區和平西路1段3號2樓（古亭8號出口）",
                      "addressLocality": "臺北市"
                    }
                  }
                }
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isEqualTo("中正區");
    }

    @Test
    void extractsDistrictWithoutCityPrefixInStreetAddress() throws Exception {
        // 真實資料：區在最前面，沒有縣市前綴（見端到端驗證抓到的真實職缺）
        JsonNode payload = objectMapper.readTree("""
                {
                  "title": "ACCUPASS Backend",
                  "hiringOrganization": {"name": "ACCUPASS"},
                  "description": "...",
                  "jobLocation": {
                    "address": {
                      "streetAddress": "中山區中山北路二段106之2號6樓",
                      "addressLocality": "臺北市"
                    }
                  }
                }
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isEqualTo("中山區");
    }

    @Test
    void leavesDistrictNullWhenStreetAddressHasNoDistrictPattern() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {
                  "title": "Remote Role",
                  "hiringOrganization": {"name": "Acme"},
                  "description": "...",
                  "jobLocation": {
                    "address": {
                      "streetAddress": "Remote",
                      "addressLocality": "臺北市"
                    }
                  }
                }
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isNull();
    }

    private String fixture(String name) throws Exception {
        try (var in = new ClassPathResource("fixtures/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
