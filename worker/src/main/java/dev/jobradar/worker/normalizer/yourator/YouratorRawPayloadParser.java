package dev.jobradar.worker.normalizer.yourator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.worker.normalizer.NormalizedJob;
import dev.jobradar.worker.normalizer.RawPayloadParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 解析 schema.org JobPosting JSON-LD（見 design.md 附錄）。baseSalary 不一定存在
 * （面議職缺沒有這個欄位），salaryMin/Max 在這種情況下留 null。
 */
@Component
public class YouratorRawPayloadParser implements RawPayloadParser {

    private static final String SOURCE = "yourator";

    // 區級資訊只存在於 streetAddress 這個自由文字欄位，位置不固定（見 job-location-extraction
    // spec）。字元類排除「市/縣/區」本身，避免像「臺北市中正區」這種字串誤抓成「市中正區」
    // （排除後才能正確定位到「中正區」，見規格文件的範例驗證）。
    private static final Pattern DISTRICT_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fff&&[^市縣區]]{2,3}區");

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

        // employmentType 是 JSON-LD 標準欄位（FULL_TIME 等）；seniority/jobType/lang/年資/
        // 開缺數在 Yourator 的 JobPosting JSON-LD 裡沒有對應欄位，留 null
        String employmentType = payload.path("employmentType").asText(null);

        String city = normalizeCity(payload.path("jobLocation").path("address")
                .path("addressLocality").asText(null));
        String district = extractDistrict(payload.path("jobLocation").path("address")
                .path("streetAddress").asText(null));

        return new NormalizedJob(title, company, salaryMin, salaryMax, salaryCurrency, description,
                employmentType, null, null, null, null, null, city, district);
    }

    /**
     * Yourator 用「臺」異體字（如「臺北市」），CakeResume 用「台」通用字（如「台北市」）。
     * 統一正規化成「台」，避免跨平台地區篩選因字串不相等而漏資料（見 design.md D3）。
     */
    private String normalizeCity(String city) {
        return city == null ? null : city.replace('臺', '台');
    }

    private String extractDistrict(String streetAddress) {
        if (streetAddress == null) {
            return null;
        }
        Matcher matcher = DISTRICT_PATTERN.matcher(streetAddress);
        return matcher.find() ? matcher.group() : null;
    }
}
