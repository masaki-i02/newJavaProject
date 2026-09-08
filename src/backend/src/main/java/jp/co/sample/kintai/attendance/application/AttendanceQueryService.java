package jp.co.sample.kintai.attendance.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEntry;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;

/**
 * 日次勤怠の参照。
 *
 * <p>{@code readOnly} なトランザクション境界をこの層に置く（アーキテクチャ設計書 6.1）。
 *
 * <p><strong>閲覧範囲の判定をここで行う</strong>（要件定義書 4.1）。
 * 「配下部署の社員か」は組織の状態に依存する業務判断なので、
 * Spring Security の認可設定では表現できない。
 *
 * <p>依頼者は<strong>引数で受け取る。</strong>
 * 認証の枠組みから読みにいく形にすると、ユースケースが
 * 「誰の依頼か」を差し替えられなくなり、バッチからも呼べなくなる
 * （CLAUDE.md 落とし穴 42）。
 */
@Service
@Transactional(readOnly = true)
public class AttendanceQueryService {

    private final DailyAttendanceRepository dailyAttendances;
    private final TimeClockEventRepository timeClocks;
    private final EmployeeVisibility visibility;

    public AttendanceQueryService(DailyAttendanceRepository dailyAttendances,
                                  TimeClockEventRepository timeClocks,
                                  EmployeeVisibility visibility) {
        this.dailyAttendances = dailyAttendances;
        this.timeClocks = timeClocks;
        this.visibility = visibility;
    }

    /**
     * 楽観ロックの版。
     *
     * <p><strong>取得する経路が無いと、利用者は版を送れない</strong>
     * （05 API設計書 1.1）。再計算はこれを必須にしている。
     * 行が無い日は 0 を返す（落とし穴 57）。
     */
    @Transactional(readOnly = true)
    public long currentVersion(Requester requester, EmployeeId employeeId,
                               LocalDate workDate) {
        requireVisible(requester, employeeId, workDate);
        return dailyAttendances.currentVersion(employeeId, workDate);
    }

    public Optional<DailyAttendance> find(Requester requester, EmployeeId employeeId,
                                          LocalDate workDate) {
        // ★ 基準日はその勤務日。今日の組織で過去の勤怠の可否を決めない。
        //   異動した部下の異動前の勤怠を、旧上長が見られなくなるのを防ぐ
        requireVisible(requester, employeeId, workDate);
        return dailyAttendances.find(employeeId, workDate);
    }

    /**
     * 期間の日次勤怠（[03 API設計書 3.1]）。
     *
     * <p><strong>期間は半開区間で受ける。暦月に固定しない。</strong>
     * 月中入社の初月は「入社日から翌月 1 日まで」であり、
     * 暦月で受けると入社前の日まで問い合わせることになる。
     *
     * <p>閲覧の可否は<strong>期間の最終日</strong>（半開区間の上限の前日）で見る。
     * 上限そのもので見ると、翌月に異動した部下の当月ぶんが見られなくなる。
     */
    public List<DailyAttendance> findByPeriod(Requester requester, EmployeeId employeeId,
                                              LocalDate from, LocalDate toExclusive) {
        DateRange period = requireValidPeriod(from, toExclusive);
        requireVisible(requester, employeeId, period.toExclusive().minusDays(1));
        return dailyAttendances.findByPeriod(employeeId, period);
    }

    /**
     * 期間の妥当性。
     *
     * <p><strong>{@code DateRange} の compact constructor に任せない。</strong>
     * 利用者が送る値なので、{@code IllegalArgumentException} のまま素通しすると
     * 理由の載らない 500 になる（CLAUDE.md 落とし穴 105）。
     *
     * <p>上限を置く。置かないと 1000 年ぶんを 1 回の要求で読み出せる。
     * 画面が使うのは 1 か月ぶんなので、うるう年の 1 年（366 日）あれば足りる。
     */
    private static DateRange requireValidPeriod(LocalDate from, LocalDate toExclusive) {
        if (from == null || toExclusive == null) {
            throw new InvalidPeriodException("期間の指定がありません");
        }
        if (!from.isBefore(toExclusive)) {
            throw new InvalidPeriodException(
                    "期間の終わりは始まりより後でなければなりません: %s 〜 %s"
                            .formatted(from, toExclusive));
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, toExclusive);
        if (days > MAX_PERIOD_DAYS) {
            throw new InvalidPeriodException(
                    "一度に照会できるのは %d 日までです: %d 日".formatted(MAX_PERIOD_DAYS, days));
        }
        return new DateRange(from, toExclusive);
    }

    /** 一度に照会できる日数の上限。 */
    private static final long MAX_PERIOD_DAYS = 366;

    /** 期間の指定が不正。<strong>実装の不備ではなく業務エラーとして返す。</strong> */
    public static final class InvalidPeriodException
            extends jp.co.sample.kintai.shared.domain.DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        InvalidPeriodException(String message) {
            super(message);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-period";
        }

        @Override
        public jp.co.sample.kintai.shared.domain.DomainErrorKind kind() {
            return jp.co.sample.kintai.shared.domain.DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "期間の指定が不正です";
        }
    }

    /**
     * その勤務日の打刻を<strong>識別子つき</strong>で返す（BR-09）。
     *
     * <p><strong>訂正申請の画面がこれを必要とする。</strong>
     * 取消の対象は打刻の識別子で指すので、識別子を返す経路が無いと
     * 利用者は「どの打刻を取り消すか」を選べず、
     * 実在しない識別子を送って外部キー違反にするしかなくなる（落とし穴 66）。
     *
     * <p><strong>取り消された打刻も含めて返す。</strong>
     * 有効な打刻だけだと「元は何時だったか」を示せず、
     * 何がどう直ったのかを利用者が確かめられない（BR-09 の目的）。
     *
     * <p>日次勤怠と同じ閲覧範囲で絞る。承認者は部下の打刻を見て訂正申請を判断する。
     */
    public List<TimeClockEntry> recordedOn(Requester requester, EmployeeId employeeId,
                                           LocalDate workDate) {
        requireVisible(requester, employeeId, workDate);
        return timeClocks.findEntriesByWorkDate(employeeId, workDate);
    }

    private void requireVisible(Requester requester, EmployeeId target, LocalDate asOf) {
        if (!visibility.canView(requester, target, asOf)) {
            throw new AccessDeniedException();
        }
    }
}
