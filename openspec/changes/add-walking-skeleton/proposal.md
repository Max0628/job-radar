# Proposal: add-walking-skeleton

## Why

job-radar 目前只有藍圖（docs/architecture.md），沒有任何程式碼。第一步不做完整功能，
而是用最小範圍讓每個架構元件都被穿過一次，及早暴露整合風險（Kafka 合約、冪等寫入、
CA 信任、GitOps 流程），之後的 change 都在這條骨架上長肉。

## What Changes

Yourator 單一關鍵字走通全管線，且是「部署在 homelab cluster、由 GitOps 管理」的狀態：

- Gradle multi-module 骨架（common / collector / worker / api，依 D1、D7）
- collector：scheduler + Yourator list scraper，淺掃（時間游標 + early termination，依 D6）
- worker：detail fetcher（限速）→ normalizer（冪等 upsert + 快照，依 D5）→ Discord notifier，
  三個 consumer 獨立 consumer group（D8），經 `jobs.discovered` / `jobs.raw` / `jobs.events`（D2、D3）
- PostgreSQL 六張表 + Flyway migration（D4）
- api：只需 health + Prometheus endpoint 存活
- CI/CD：.gitlab-ci.yml → GitLab Registry（D9）→ 更新 k8s repo image tag → ArgoCD sync
- k8s repo manifests（Kafka、PG、三個 Deployment、SealedSecrets，依 D10）
- 前置作業：homelab Root CA 裝進 k8s 節點信任庫（homelab-infra repo）

## Non-goals（本 change 不做）

- 104 或第二個來源、深掃節奏、CHANGED/CLOSED 事件（content_hash 先算先存，事件只發 NEW）
- REST API 業務端點、前端
- Grafana dashboard、Alertmanager 規則（metrics endpoint 要有，看板後補）
- transactional outbox（D11：v1 接受可能漏事件）、跨平台去重、LLM extraction

## Impact

- 新增：job-radar 全部初始程式碼、k8s repo 的 `apps/job-radar/`、homelab-infra 的 CA playbook
- 風險：Yourator API 形狀未調查（Task 第一項）；節點 CA 信任是部署的硬前置
