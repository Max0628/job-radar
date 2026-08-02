package dev.jobradar.common.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 支援的求職平台來源，取代原本散落各處的裸字串（'yourator'/'cakeresume'/'104'）。
 *
 * {@code JOB104} 而非 {@code 104}：Java enum 常數名稱不能以數字開頭，沿用既有的
 * `job104` package／`Job104ListScraper` 等命名慣例。
 *
 * {@code @JsonValue}/{@code @JsonCreator}：讓 Jackson 序列化/反序列化時仍使用原本的
 * 小寫字串（"yourator"/"cakeresume"/"104"），不是 enum 常數名稱（"YOURATOR"/"JOB104"）
 * ——這樣 Kafka envelope 的既有 JSON 格式、REST API 對前端的回應格式都不需要改變，
 * 是純粹的 Java 端型別安全強化，不是 wire format 的破壞性變更。
 */
public enum Source {
    YOURATOR("yourator"),
    CAKERESUME("cakeresume"),
    JOB104("104");

    private final String value;

    Source(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Source fromValue(String value) {
        for (Source source : values()) {
            if (source.value.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown source: " + value);
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Strategy pattern 的共用註冊工具：collector/worker/api 各有一組「Spring 注入一份
     * per-source 實作清單、依 source() 建成查找表」的樣板（見 ScanService/DetailFetcherListener/
     * NormalizerListener/FacetsService），4 處寫法幾乎一模一樣，收斂到這裡避免重複。
     * 用 {@code EnumMap} 而非泛型 {@code HashMap}——key 是這個封閉的 enum，語意跟效能都更合適。
     */
    public static <T> Map<Source, T> indexBy(List<T> items, Function<T, Source> sourceOf) {
        Map<Source, T> result = new EnumMap<>(Source.class);
        for (T item : items) {
            result.put(sourceOf.apply(item), item);
        }
        return result;
    }
}
