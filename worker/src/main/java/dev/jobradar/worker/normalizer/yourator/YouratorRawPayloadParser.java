package dev.jobradar.worker.normalizer.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.worker.normalizer.NormalizedJob;
import dev.jobradar.worker.normalizer.RawPayloadParser;
import org.springframework.stereotype.Component;

/**
 * 解析 schema.org JobPosting JSON-LD（見 design.md 附錄）。baseSalary 不一定存在
 * （面議職缺沒有這個欄位），salaryMin/Max 在這種情況下留 null。
 */
@Component
public class YouratorRawPayloadParser implements RawPayloadParser {

    private static final String SOURCE = "yourator";

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public NormalizedJob parse(JsonNode payload) {
        String title = payload.path("title").asText(null);
        String company = payload.path("hiringOrganization").path("name").asText(null);
        String description = payload.path("description").asText(null);

        JsonNode salaryValue = payload.path("baseSalary").path("value");
        Long salaryMin = salaryValue.hasNonNull("minValue") ? salaryValue.get("minValue").asLong() : null;
        Long salaryMax = salaryValue.hasNonNull("maxValue") ? salaryValue.get("maxValue").asLong() : null;
        String salaryCurrency = payload.path("baseSalary").path("currency").asText(null);

        return new NormalizedJob(title, company, salaryMin, salaryMax, salaryCurrency, description);
    }
}
