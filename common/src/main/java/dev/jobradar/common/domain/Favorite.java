package dev.jobradar.common.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.jobradar.common.source.Source;
import java.time.Instant;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * favorites 表的一列。單使用者，不需要 user_id（見 add-job-dashboard/design.md D6）。
 * (source, sourceJobId) 是唯一鍵。
 *
 * {@code @JsonAutoDetect(fieldVisibility = ANY)}：fluent 存取方法 Jackson 序列化時看不到，
 * 這個類別會被 FavoriteController 直接序列化成回應，見 Job.java 的說明。
 */
@Value
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class Favorite {
    long id;
    Source source;
    String sourceJobId;
    Instant createdAt;
}
