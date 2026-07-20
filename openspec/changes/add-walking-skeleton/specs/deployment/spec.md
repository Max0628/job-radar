# Spec: deployment

## ADDED Requirements

### Requirement: GitOps 全自動部署
系統 SHALL 以 git push 為唯一部署動作：GitLab CI 跑測試、建 image、推自架 Registry、
更新 k8s repo image tag，ArgoCD 自動 sync，全程無手動步驟。

#### Scenario: 一次 push 上線新版本
- **WHEN** 開發者 push 到 job-radar main
- **THEN** CI 綠燈後，cluster 內三個 Deployment 在 ArgoCD sync 後運行新 image

### Requirement: 可觀測性最低限度
三個服務 SHALL 暴露 Prometheus metrics endpoint 並被既有 kube-prometheus-stack 抓取，
log 以結構化 JSON 輸出供 Loki 收集。

#### Scenario: metrics 可見
- **WHEN** 服務部署完成
- **THEN** 三個 pod 出現在 Prometheus targets 且 up=1
