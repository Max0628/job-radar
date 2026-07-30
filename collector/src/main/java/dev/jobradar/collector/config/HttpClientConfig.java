package dev.jobradar.collector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 覆蓋 Spring Boot 自動組態的 RestClient.Builder（沒特別設定時 connect/read timeout
 * 用底層 client 的預設值，實測偏短——見 add-crawl-improvements：拿掉頁數上限後一輪
 * 掃描要打的請求數變多，沒設過的預設 timeout 在請求量變多時遇到偶發慢回應的機率
 * 也跟著變高，部署後實測連續三次掃描都因為 SocketTimeoutException 失敗）。
 *
 * 刻意在這裡設定，而不是在各自 scraper 的建構子裡呼叫
 * {@code restClientBuilder.requestFactory(...)}——單元測試用
 * {@code MockRestServiceServer.bindTo(builder)} 綁定 mock 時，如果 scraper 的
 * 建構子事後又呼叫一次 requestFactory()，會把 mock 的攔截機制整個覆蓋掉、變成真的
 * 打外部網路（已實際踩過：測試裡的 pagesScanned 從預期的 2 變成 60，因為請求真的
 * 打到 yourator.co 本尊）。改在這裡的 Bean 定義自訂 timeout，測試建構 scraper 時
 * 是直接手動組一個乾淨的 {@code RestClient.builder()} 綁定 mock，完全不會經過
 * 這個 Bean，兩邊互不干擾。
 *
 * {@code @Scope("prototype")} 沿用 Spring Boot 自動組態的 RestClient.Builder 預設
 * 範圍——每個注入點都要拿到獨立的 builder 實例，各自呼叫 baseUrl()／
 * defaultHeader() 才不會互相汙染。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(30_000);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
