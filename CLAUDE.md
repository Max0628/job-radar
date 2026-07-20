# job-radar

個人用職缺聚合工具：爬取求職平台 → Kafka 管線 → PostgreSQL → Discord 推播。
部署於 homelab K8s（`~/projects/homelab-infra`），走 GitLab CI + ArgoCD GitOps。
同時是使用者（momo 後端工程師，目標轉 SRE/Infra）的面試作品集。

## 開發流程：SDD，用 OpenSpec 框架

1. **動手前先讀 `docs/architecture.md`**——所有架構決策與理由（D1–D12）都在那裡，含被否決的選項。**不要重新討論已決策事項**（語言、Kafka、兩段式爬蟲、儲存設計等）
2. Spec 工作全部走 OpenSpec（`openspec/`，slash commands `/opsx:*`）。目前進行中的 change：`openspec/changes/add-walking-skeleton/`（proposal / design / specs / tasks 已齊，可直接 `/opsx:apply`）
3. 新 feature 用 `/opsx:propose` 開 change；實作偏離 spec 時先更新 artifact 再改程式碼；調查結果（如平台 API 形狀）寫回該 change 的 design.md 附錄
4. Change 完成、驗收 scenario 全過後 `/opsx:archive`

## 技術守則

- Java 21 + Spring Boot 3.x + Gradle multi-module。**virtual threads + blocking style，禁用 WebFlux**
- 所有 Kafka consumer 必須冪等（at-least-once 前提），寫入用 upsert / insert-ignore
- 訊息 envelope 定義在 `common`，改格式必須遞增 `schemaVersion`
- DB migration 一律走 Flyway，不手動改 schema
- 對外部平台的 request：同來源低並發（≤2）、加間隔、429 退避——爬蟲禮貌是硬規則
- 三個 boot jar：`collector` / `worker` / `api`；worker 內 consumer 各自獨立 consumer group
- JVM `-Xmx` ≤ 512MB（T480 資源有限），metrics 走 Micrometer/Prometheus，log 出結構化 JSON

## 相關位置

- 部署 manifests：獨立的 `k8s` repo（ArgoCD 同步來源），不放本 repo
- 平台層（cluster、GitLab、觀測性）：`~/projects/homelab-infra`，本 repo 不管平台
- 前置作業清單（CA 信任、Sealed Secrets controller 等）：見 `specs/architecture.md` 前置作業章節
