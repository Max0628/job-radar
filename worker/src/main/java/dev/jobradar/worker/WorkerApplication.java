package dev.jobradar.worker;

import dev.jobradar.worker.notifier.DiscordProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// scanBasePackages 明確加 dev.jobradar.common：Spring Boot 預設只掃描主程式類別所在的
// package（dev.jobradar.worker），共用的 JobExistenceRepository（@Repository）放在
// dev.jobradar.common.repository，不在預設掃描範圍內，不加這個 bean 會直接抓不到。
@SpringBootApplication(scanBasePackages = {"dev.jobradar.worker", "dev.jobradar.common"})
@EnableConfigurationProperties(DiscordProperties.class)
@EnableScheduling
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
