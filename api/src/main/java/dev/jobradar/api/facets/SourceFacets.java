package dev.jobradar.api.facets;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.List;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * 某個來源（yourator/cakeresume）目前可用的分類與地區清單，即時從平台官方 API 抓，
 * 由 {@link FacetsService} 快取（見 add-crawl-improvements design.md）。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SourceFacets {
    List<Facet> categories;
    List<Facet> locations;
}
