# Tasks: add-walking-skeleton

## 1. 前置作業（跨 repo）

- [x] 1.1a 【homelab-infra】新增 `install-registry-ca-trust.yml` playbook：homelab Root CA
      裝進三台 k8s 節點信任庫 → `update-ca-certificates` → 重啟 containerd。已 syntax-check 通過
- [ ] 1.1b 【需使用者確認】對真實三個節點執行 1.1a 的 playbook，驗證節點可拉
      `registry.192.168.100.200.nip.io` 的 image（見 spec 001 附錄「執行環境探查」）
- [ ] 1.2 【需使用者確認】cluster 安裝 Sealed Secrets controller（helm）
- [ ] 1.3 【需使用者操作】GitLab 建立 `job-radar` project，確認 Runner 與 Registry push 可用
- [x] 1.4 建立 Discord webhook。已用真實 webhook 跑過 worker 驗證：5 則真實新職缺通知
      成功送達（雙保險：webhook URL 只當 shell 環境變數傳入、未寫入任何檔案，
      驗證後已停止程序並清掉暫存 log）。正式部署時仍要走 Secret（見 `secrets.example.yaml`）

## 2. 調查

- [x] 2.1 Yourator API 調查（list/detail endpoint、分頁、排序、欄位、rate limit 觀察），
      結果寫入 design.md 附錄。關鍵發現：list 無精確排序，改用固定翻頁策略（見附錄）

## 3. 程式碼骨架

- [x] 3.1 Gradle multi-module（Kotlin DSL）：common / collector / worker / api，Java 21 toolchain
- [x] 3.2 common：envelope record（DiscoveredEnvelope/RawEnvelope/JobEventEnvelope）、
      topic 常數、domain model
- [x] 3.3 Flyway migration V1+V2：六張表 + search_queries 種子資料
- [x] 3.4 本機 docker-compose：Kafka（KRaft，podman-compose 驗證可跑）+ PostgreSQL

## 4. 管線實作

- [x] 4.1 collector：scheduler + Yourator list adapter + scrape_runs 記錄。實際對 Yourator
      跑過（200 筆真實職缺，10 頁，成功發布 jobs.discovered）
- [x] 4.2 worker/fetcher：discovered 消費、查庫判斷、限速（明確 interval gate）、發 raw。
      實際對 Yourator detail 頁跑過 130 筆真實資料
- [x] 4.3 worker/normalizer：raw 消費、raw_documents、冪等 upsert（xmax=0 判斷 NEW）、
      快照、發 events。已用真實資料驗證入庫
- [x] 4.4 worker/notifier：events 消費、Discord embed 推播（webhook 未設定時正確跳過並記錄警告）
- [x] 4.5 DLQ：DefaultErrorHandler 重試 3 次 → DeadLetterPublishingRecoverer（三個 topic 各自
      獨立 consumer group + container factory，見 KafkaConsumerConfig）
- [x] 4.6 測試：10 個測試全過——normalizer 冪等/重放（Testcontainers PG）、
      YouratorListScraper（MockRestServiceServer + 真實 fixture）、
      YouratorRawPayloadParser + ContentHash 單元測試

## 5. CI/CD 與部署（跨 repo）

- [x] 5.1 Dockerfile（layered jar，`-Djarmode=tools extract --layers --launcher`）×3 +
      `.gitlab-ci.yml`（test → build → kaniko package → 更新 k8s repo tag）。
      collector image 已用 podman build+run 實際跑通，容器內成功連上 Kafka/PG
- [x] 5.2 【k8s repo】`apps/job-radar/` 純 YAML manifest（無 kustomize，見 design.md 對
      root app `directory.recurse: true` 的實際確認）：Kafka/PG StatefulSet、三個 Deployment
      （resources/-Xmx 依硬規則）、Service ×3、ServiceMonitor ×3（label 對齊真實
      `serviceMonitorSelector`）。全部通過 `kubectl apply --dry-run` 驗證。
      SealedSecrets 待 1.2 完成後才能產生真正的檔案（見 `secrets.example.yaml`）
- [x] 5.3 ServiceMonitor（見 5.2）+ 結構化 JSON log 設定（logstash-logback-encoder，三模組皆有）
- [ ] 5.4 【需使用者確認】端到端驗收：真的 push → CI → ArgoCD → 收到 Discord 推播；
      勾掉三個 spec 檔的所有 scenario（本機管線邏輯已驗證，缺的是「跑在 GitOps 上」這一段）
