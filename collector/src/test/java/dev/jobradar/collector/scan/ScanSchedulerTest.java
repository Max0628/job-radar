package dev.jobradar.collector.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ScanSchedulerTest {

    // 依賴都不會被 isWithinActiveHours() 用到，傳 null 即可，避免為了測一個時段判斷式
    // 另外引入 mocking 框架（見 ScanScheduler 的 package-private 註解）
    private final ScanScheduler scheduler = new ScanScheduler(
            null, null, null,
            new CollectorScanProperties(300_000, 0, "test-agent", 0, 8, 23));

    private Instant taipeiTimeAt(int hour) {
        return ZonedDateTime.now(ZoneId.of("Asia/Taipei"))
                .withHour(hour).withMinute(0).withSecond(0).withNano(0)
                .toInstant();
    }

    @Test
    void withinActiveHoursReturnsTrue() {
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(8))).isTrue();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(14))).isTrue();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(22))).isTrue();
    }

    @Test
    void startHourIsInclusive() {
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(8))).isTrue();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(7))).isFalse();
    }

    @Test
    void endHourIsExclusive() {
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(22))).isTrue();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(23))).isFalse();
    }

    @Test
    void midnightAndEarlyMorningAreOutsideActiveHours() {
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(0))).isFalse();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(3))).isFalse();
        assertThat(scheduler.isWithinActiveHours(taipeiTimeAt(6))).isFalse();
    }
}
