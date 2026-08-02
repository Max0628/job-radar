package dev.jobradar.collector.config;

import dev.jobradar.collector.scan.CollectorScanProperties;
import dev.jobradar.common.source.CakeResumeEndpoints;
import dev.jobradar.common.source.Job104Endpoints;
import dev.jobradar.common.source.YouratorEndpoints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 各來源專屬 RestClient 的組裝點：baseUrl／header 差異全部集中在這裡，scraper 端
 * 只單純注入已組好的 client（見各 XxxListScraper 的 {@code @RequiredArgsConstructor}）。
 *
 * 三個 Bean 回傳型別都是 RestClient，Spring 靠「注入點欄位名稱＝Bean 方法名稱」的
 * fallback 規則消歧義，不用 {@code @Qualifier}——因此各 scraper 裡對應欄位務必命名成
 * 跟這裡的 Bean 方法名稱一致（如 {@code cakeResumeRestClient}）。
 *
 * connect/read timeout 統一由注入的 restClientBuilder 帶（見 HttpClientConfig），
 * 這裡不重覆設定。
 *
 * 單元測試完全不會經過這個類別：測試直接手動組一個乾淨的 RestClient.Builder、綁
 * MockRestServiceServer、build() 後把結果傳進 scraper 建構子，不載入 Spring context
 * （見各 XxxListScraperTest）。
 */
@Configuration
public class ScraperClientConfig {

    @Bean
    public RestClient cakeResumeRestClient(RestClient.Builder restClientBuilder, CollectorScanProperties properties) {
        return restClientBuilder
                .baseUrl(CakeResumeEndpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.REFERER, "https://www.cake.me/jobs")
                .build();
    }

    @Bean
    public RestClient job104RestClient(RestClient.Builder restClientBuilder, CollectorScanProperties properties) {
        return restClientBuilder
                .baseUrl(Job104Endpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .defaultHeader(HttpHeaders.REFERER, "https://www.104.com.tw/jobs/search/")
                .build();
    }

    @Bean
    public RestClient youratorRestClient(RestClient.Builder restClientBuilder, CollectorScanProperties properties) {
        return restClientBuilder
                .baseUrl(YouratorEndpoints.BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

}
