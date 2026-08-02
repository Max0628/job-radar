package dev.jobradar.common.domain;

/**
 * 淺掃/深掃（見 architecture.md D6：同一套掃描邏輯的一個「提早停止」開關，不是兩套系統）。
 *
 * {@code dbValue}：既有 `scrape_runs.scan_mode` 欄位已經存了小寫的 "deep"/"light"（不符合
 * Java enum 慣例的大寫命名），用 dbValue 顯式映射保留原本的字串表示，避免直接用
 * {@code name()}／{@code valueOf()} 導致跟已存在的 DB 資料格式不一致。
 */
public enum ScanMode {
    DEEP("deep"),
    LIGHT("light");

    private final String dbValue;

    ScanMode(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ScanMode fromDbValue(String value) {
        for (ScanMode mode : values()) {
            if (mode.dbValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown scan mode: " + value);
    }
}
