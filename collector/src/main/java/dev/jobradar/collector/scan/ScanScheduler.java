package dev.jobradar.collector.scan;

import dev.jobradar.common.domain.SearchQuery;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);
    private static final ZoneId TARGET_TIMEZONE = ZoneId.of("Asia/Taipei");

    private final EnabledSearchQueryRepository searchQueryRepository;
    private final ScrapeCursorRepository cursorRepository;
    private final ScanService scanService;
    private final CollectorScanProperties properties;
    private final Random random = new Random();

    @Scheduled(fixedDelayString = "${collector.scan.tick-interval-millis}", initialDelayString = "10000")
    public void tick() {
        if (!isWithinActiveHours(Instant.now())) {
            log.debug("Outside active scanning hours ({}-{} {}), skipping this tick",
                    properties.activeHoursStart(), properties.activeHoursEnd(), TARGET_TIMEZONE);
            return;
        }

        // 三來源（未來含 104）平行執行，取代原本的循序 for 迴圈（見 architecture.md D6）。
        // 並發限制是 per-source 的（各 ListScraper 自己節流），不同來源本來就打不同網站，
        // 同時進行不算「疊加流量」。虛擬執行緒搭配 try-with-resources：close() 會等所有
        // 已送出的任務跑完才返回，維持 tick() 整體同步、不會跟下一次排程觸發重疊執行。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SearchQuery query : searchQueryRepository.findAllEnabled()) {
                executor.submit(() -> runScanIfDue(query));
            }
        }
    }

    private void runScanIfDue(SearchQuery query) {
        try {
            if (isDue(query)) {
                scanService.runScan(query);
            }
        } catch (Exception e) {
            log.error("Unexpected error while checking/running scan for query id={}", query.id(), e);
        }
    }

    /**
     * 半夜掃描是不自然的流量模式——真實使用者不會在深夜大量瀏覽職缺網站，這種時段的請求
     * 對平台來說是異常流量的訊號，容易被判定為機器人而封鎖。限制在一般人會使用求職網站
     * 的時段（預設台灣時間 8-23 點），讓爬蟲的請求模式更接近正常人類使用習慣。
     * 這個檢查獨立於 isDue() 的間隔判斷之上：就算某個 query 已經到了該掃的時間，
     * 若當下在非活躍時段，這輪照樣跳過，等下一次 tick 落在活躍時段內才會真的觸發。
     */
    // package-private 而非 private：讓測試能直接餵 Instant 驗證邊界情況，
    // 不用為了這一個判斷式整套引入 Clock 抽象（見 ScanSchedulerTest）
    boolean isWithinActiveHours(Instant now) {
        int hour = ZonedDateTime.ofInstant(now, TARGET_TIMEZONE).getHour();
        return hour >= properties.activeHoursStart() && hour < properties.activeHoursEnd();
    }

    private boolean isDue(SearchQuery query) {
        Optional<Instant> lastScannedAt = cursorRepository.findLastScannedAt(query.id());
        if (lastScannedAt.isEmpty()) {
            return true;
        }

        long jitterSeconds = properties.jitterSeconds() > 0 ? random.nextInt(properties.jitterSeconds()) : 0;
        Instant dueAt = lastScannedAt.get()
                .plusSeconds((long) query.intervalMinutes() * 60)
                .plusSeconds(jitterSeconds);
        return !Instant.now().isBefore(dueAt);
    }
}
