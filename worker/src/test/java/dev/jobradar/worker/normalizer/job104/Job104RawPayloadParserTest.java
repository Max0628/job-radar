package dev.jobradar.worker.normalizer.job104;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.worker.normalizer.NormalizedJob;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class Job104RawPayloadParserTest {

    private final Job104RawPayloadParser parser = new Job104RawPayloadParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesFieldsFromDetailResponse() throws Exception {
        JsonNode payload = objectMapper.readTree(fixture("job104-detail.json"));

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.title()).isEqualTo("QA Engineer");
        assertThat(normalized.company()).isEqualTo("Garena Online Private Limited台灣分公司");
        assertThat(normalized.description()).contains("負責遊戲軟體品質保證");
        assertThat(normalized.salaryMin()).isEqualTo(45000L);
        assertThat(normalized.salaryMax()).isEqualTo(65000L);
        assertThat(normalized.salaryCurrency()).isEqualTo("TWD");
        assertThat(normalized.jobType()).isEqualTo("1");
        // fixture: addressArea="台北市"、addressRegion="台北市內湖區" → district 是去掉
        // 前綴後的「內湖區」（見 Job104RawPayloadParser.extractDistrict）
        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isEqualTo("內湖區");
        // fixture 的 appearDate 是 "2026/07/31"
        assertThat(normalized.postedAt()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    }

    /**
     * fixture 是這次真實打 104 detail API 取樣到的一筆面議職缺（去除聯絡資訊等非必要
     * 欄位後存檔，見 104-api-poc/samples/detail-8wfs1-raw.json 的原始回應）。實測發現
     * 104 的面議職缺不是回傳 JSON null（跟 Yourator/CakeResume 不同），而是
     * salaryMin/salaryMax 都填數字 0——直接存 0 會誤導成「時薪 0 元」，見
     * Job104RawPayloadParser 的正規化邏輯。
     */
    @Test
    void normalizesZeroSalaryToNullForNegotiableJobs() throws Exception {
        JsonNode payload = objectMapper.readTree(fixture("job104-detail-negotiable-salary.json"));

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.title()).isEqualTo("Java工程師");
        assertThat(normalized.company()).isEqualTo("昇智科技有限公司");
        assertThat(normalized.salaryMin()).isNull();
        assertThat(normalized.salaryMax()).isNull();
        assertThat(normalized.city()).isEqualTo("台北市");
        assertThat(normalized.district()).isEqualTo("松山區");
        assertThat(normalized.postedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void handlesMissingSalaryAsNull() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {
                  "data": {
                    "header": {"jobName": "Negotiable Role", "custName": "Acme"},
                    "jobDetail": {"jobDescription": "..."}
                  }
                }
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.salaryMin()).isNull();
        assertThat(normalized.salaryMax()).isNull();
        assertThat(normalized.salaryCurrency()).isEqualTo("TWD");
        assertThat(normalized.city()).isNull();
        assertThat(normalized.district()).isNull();
        assertThat(normalized.postedAt()).isNull();
    }

    @Test
    void returnsNullPostedAtWhenAppearDateFormatIsUnparseable() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {
                  "data": {
                    "header": {"jobName": "Malformed Date Role", "custName": "Acme", "appearDate": "not-a-real-date"},
                    "jobDetail": {"jobDescription": "..."}
                  }
                }
                """);

        NormalizedJob normalized = parser.parse(payload);

        assertThat(normalized.postedAt()).isNull();
    }

    @Test
    void leavesDistrictNullWhenAddressRegionDoesNotStartWithAddressArea() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {
                  "data": {
                    "header": {"jobName": "Remote Role", "custName": "Acme"},
                    "jobDetail": {
                      "jobDescription": "...",
                      "addressArea": "台北市",
                      "addressRegion": "新北市板橋區"
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
