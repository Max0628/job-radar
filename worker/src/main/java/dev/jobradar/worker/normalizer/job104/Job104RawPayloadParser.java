package dev.jobradar.worker.normalizer.job104;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.worker.normalizer.NormalizedJob;
import dev.jobradar.worker.normalizer.RawPayloadParser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 解析 104 detail API 回應（{@code data} 底下，見 source-api-notes.md 欄位對照表）。
 */
@Component
public class Job104RawPayloadParser implements RawPayloadParser {

    private static final Logger log = LoggerFactory.getLogger(Job104RawPayloadParser.class);
    private static final String SOURCE = "104";

    // header.appearDate 格式如 "2026/07/31"：斜線分隔，跟 list API 的 appearDate
    // （緊湊數字 "20260731"）格式不同，需要自訂 pattern（見 design.md，比照 Yourator
    // 處理 datePosted 的手法）
    private static final DateTimeFormatter APPEAR_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public NormalizedJob parse(JsonNode payload) {
        JsonNode data = payload.path("data");

        String title = data.path("header").path("jobName").asText(null);
        String company = data.path("header").path("custName").asText(null);

        JsonNode jobDetail = data.path("jobDetail");
        String description = jobDetail.path("jobDescription").asText(null);
        Long salaryMin = jobDetail.hasNonNull("salaryMin") ? jobDetail.get("salaryMin").asLong() : null;
        Long salaryMax = jobDetail.hasNonNull("salaryMax") ? jobDetail.get("salaryMax").asLong() : null;
        // 面議職缺不是回傳 JSON null（跟 Yourator/CakeResume 不同），而是 salaryMin/Max
        // 都填 0（實測樣本：jobDetail.salary="待遇面議" 時 salaryMin/salaryMax 皆為 0），
        // 直接存 0 會誤導成「時薪 0 元」，比照缺值一樣正規化成 null
        if (salaryMin != null && salaryMax != null && salaryMin == 0 && salaryMax == 0) {
            salaryMin = null;
            salaryMax = null;
        }
        // 104 沒有回傳幣別欄位，市場固定是新台幣（見 design.md）
        String salaryCurrency = "TWD";
        String jobType = jobDetail.hasNonNull("jobType") ? jobDetail.get("jobType").asText() : null;

        String city = jobDetail.path("addressArea").asText(null);
        String district = extractDistrict(jobDetail.path("addressRegion").asText(null), city);

        Instant postedAt = parseAppearDate(data.path("header").path("appearDate").asText(null));

        return new NormalizedJob(title, company, salaryMin, salaryMax, salaryCurrency, description,
                null, null, jobType, null, null, null, city, district, postedAt);
    }

    /**
     * addressRegion（如「台北市內湖區」）是市+區合併字串，去掉 addressArea（如「台北市」）
     * 這個前綴，剩下的就是區名——不做 Area.json 反查（見 design.md，跟 Yourator 的
     * district 正則手法同一類簡化）。
     */
    private String extractDistrict(String addressRegion, String addressArea) {
        if (addressRegion == null || addressArea == null || addressArea.isBlank()) {
            return null;
        }
        if (!addressRegion.startsWith(addressArea)) {
            return null;
        }
        String remainder = addressRegion.substring(addressArea.length());
        return remainder.isBlank() ? null : remainder;
    }

    /**
     * 格式跑掉時吞掉例外、回傳 null，不讓單一欄位解析失敗拖垮整筆職缺的正規化
     * （見 design.md，跟 YouratorRawPayloadParser.parseDatePosted 同一個設計原則）。
     */
    private Instant parseAppearDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text, APPEAR_DATE_FORMAT).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse 104 appearDate value \"{}\"", text, e);
            return null;
        }
    }
}
