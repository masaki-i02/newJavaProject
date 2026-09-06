package jp.co.sample.kintai.payroll.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 出力の記録のポート。実装は {@code infrastructure}。 */
public interface PayrollExportRepository {

    /**
     * 記録を保存する。
     *
     * <p><strong>実行日時は渡さない。</strong> DB の {@code now()} が打つ。
     * アプリケーションから渡せる形にすると、監査の時刻を実行者が決められる。
     */
    void save(PayrollExport export);

    Optional<PayrollExport> find(PayrollExportId id);

    /** 出力の記録。新しい順。 */
    List<PayrollExport> findByMonth(Optional<YearMonth> month, int limit);
}
