package dev.jobradar.common.domain;

/**
 * jobs.status 的合法值（見 Phase 002 design.md D6）。
 *
 * NEW：新插入的職缺，尚未被下一輪掃描重新看到過。
 * ACTIVE：曾被重新看到過的職缺（含從 NEW/CLOSED 轉來的）。
 * CLOSED：closed sweep 判定下架（見 architecture.md D12，偵測邏輯延後但欄位保留）。
 *
 * enum 常數名稱刻意跟既有 DB 欄位值（'NEW'/'ACTIVE'/'CLOSED'）逐字相同，
 * 讀寫時可直接用 {@code name()}/{@code valueOf()}，不需要額外的字串轉換層。
 */
public enum JobStatus {
    NEW,
    ACTIVE,
    CLOSED
}
