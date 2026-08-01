package dev.jobradar.common.source;

/**
 * 疑似被來源網站封鎖（403/503，見 architecture.md D19）時拋出的專用例外，跟一般性
 * 暫時錯誤（429/5xx/逾時，繼續用 IllegalStateException）分開，讓呼叫端（見
 * add-104-source/design.md「自動關閉」決策）能專門對這個情況觸發自動停用，不會
 * 誤把其他暫時性失敗也當成永久封鎖處理。
 */
public class SourceBlockedException extends RuntimeException {

    private final String source;

    public SourceBlockedException(String source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public String source() {
        return source;
    }
}
