package jp.co.sample.kintai.approval.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 訂正申請のポート。実装は {@code infrastructure}。 */
public interface CorrectionRequestRepository {

    Optional<CorrectionRequest> find(CorrectionRequestId id);

    /**
     * その勤務日の未処理の申請。
     *
     * <p><strong>同一勤務日に未処理は 1 件まで</strong>（DB の
     * {@code correction_requests_pending_uk}）。
     * 競合する訂正が同時に承認されると打刻列が壊れるためで、
     * 申請の時点でこれを確かめて分かりやすい理由を返す。
     */
    Optional<CorrectionRequest> findPending(EmployeeId employeeId, LocalDate workDate);

    /** 承認待ちの申請。承認者の一覧に使う。絞り込みは {@code application} 層が行う。 */
    List<CorrectionRequest> findPending();

    /** その社員の申請。<strong>決着したものも含む。</strong> 本人が経緯を辿れるようにする。 */
    List<CorrectionRequest> findByEmployee(EmployeeId employeeId);

    /** 新しい申請を登録する。 */
    void insert(CorrectionRequest request);

    /**
     * 決裁の結果を保存する。<strong>版が一致するときだけ</strong>（API設計書 1.1）。
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException 版が一致しない場合
     */
    void update(CorrectionRequest request, long expectedVersion);

    /** 現在の版。 */
    long currentVersion(CorrectionRequestId id);
}
