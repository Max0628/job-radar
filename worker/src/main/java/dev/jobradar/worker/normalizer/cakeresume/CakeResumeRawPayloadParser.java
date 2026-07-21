package dev.jobradar.worker.normalizer.cakeresume;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.worker.normalizer.NormalizedJob;
import dev.jobradar.worker.normalizer.RawPayloadParser;
import org.springframework.stereotype.Component;

/**
 * 解析 CakeResume search API 回應的單筆職缺物件（見 source-api-notes.md）。
 * 與 Yourator 不同，這裡的 payload 就是 discovered 階段拿到的完整資料，不是另一支 detail API
 * 的回應——needsDetail=false 時 Fetcher 直接把 discovered payload 轉成 RawEnvelope（見
 * DetailFetcherListener），因此這裡解析的欄位形狀跟 source-api-notes.md 記錄的 search response
 * 一致。
 */
@Component
public class CakeResumeRawPayloadParser implements RawPayloadParser {

    private static final String SOURCE = "cakeresume";

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public NormalizedJob parse(JsonNode payload) {
        String title = payload.path("title").asText(null);
        String company = payload.path("page").path("name").asText(null);
        String description = payload.path("description").asText(null);

        JsonNode salary = payload.path("salary");
        Long salaryMin = parseSalaryValue(salary.path("min"));
        Long salaryMax = parseSalaryValue(salary.path("max"));
        String salaryCurrency = salary.path("currency").asText(null);

        String jobType = payload.path("job_type").asText(null);
        String seniorityLevel = payload.path("seniority_level").asText(null);
        String langName = payload.path("lang_name").asText(null);
        Integer minWorkExpYear = payload.hasNonNull("min_work_exp_year")
                ? payload.get("min_work_exp_year").asInt() : null;
        Integer numberOfOpenings = payload.hasNonNull("number_of_openings")
                ? payload.get("number_of_openings").asInt() : null;

        String[] location = extractLocation(payload.path("locations"));

        // employmentType 沒有 Yourator 對應概念，CakeResume 用 job_type 表達同樣的語意，
        // 兩者不合併成單一欄位（見 design.md「欄位命名不一致」風險備註，保留平台原始語意）
        return new NormalizedJob(title, company, salaryMin, salaryMax, salaryCurrency, description,
                null, seniorityLevel, jobType, langName, minWorkExpYear, numberOfOpenings,
                location[0], location[1]);
    }

    /**
     * locations[0] 逗號切分後的段數不固定，依地區精確度而變，不能假設固定位置
     * （見 job-location-extraction spec，這是對先前版本「固定第一段是 district」錯誤假設的修正）：
     *   3 段「區, 市, 國」→ district=第一段、city=第二段
     *   2 段「市, 國」    → city=第一段、district=null
     *   其餘情況（0/1 段）→ 兩者皆 null
     *
     * @return [city, district]
     */
    private String[] extractLocation(JsonNode locations) {
        if (!locations.isArray() || locations.isEmpty()) {
            return new String[] {null, null};
        }
        String first = locations.get(0).asText(null);
        if (first == null) {
            return new String[] {null, null};
        }
        String[] parts = first.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].strip();
        }
        if (parts.length == 3) {
            return new String[] {parts[1], parts[0]};
        }
        if (parts.length == 2) {
            return new String[] {parts[0], null};
        }
        return new String[] {null, null};
    }

    /**
     * CakeResume 的 salary.min/max 觀察到兩種型態：JSON null（面議）或字串型數字（如 "1250000"，
     * 不是 JSON number），統一轉成 Long，非數字格式時保守回傳 null 而不是丟例外。
     */
    private Long parseSalaryValue(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
