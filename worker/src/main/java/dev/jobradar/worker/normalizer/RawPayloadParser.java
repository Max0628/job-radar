package dev.jobradar.worker.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.source.Source;

/**
 * 每個來源各自實作，把 detail 的原始 payload 轉成正規化欄位（見 architecture.md D3：
 * 正規化邏輯集中在 worker，不在爬蟲端）。
 */
public interface RawPayloadParser {

    Source source();

    NormalizedJob parse(JsonNode payload);
}
