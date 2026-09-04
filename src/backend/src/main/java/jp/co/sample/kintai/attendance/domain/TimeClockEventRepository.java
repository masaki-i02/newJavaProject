package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻のポート。<strong>追記のみ。</strong>
 *
 * <p>更新も削除も提供しない。打刻は労働時間の一次証拠であり、
 * 労務トラブル時に「元は何時だったか」を提示できる必要がある（BR-09）。
 * 訂正は取消打刻を追記することで表現する。
 */
public interface TimeClockEventRepository {

    /**
     * 打刻を追記する。
     *
     * @param recordedBy 記録した本人。代理打刻の証跡になる
     */
    void append(EmployeeId employeeId, LocalDate workDate, TimeClockEvent event,
                EmployeeId recordedBy);

    /**
     * 訂正の承認により打刻を追記する（BR-09）。
     *
     * <p>通常の打刻と分けるのは、{@code source} と理由を必ず伴うためである。
     * DB の {@code time_clock_events_revocation_check} が理由を必須にしている。
     *
     * @param reason 申請の理由。あとから「なぜこの打刻があるのか」を辿れるようにする
     */
    void appendCorrection(EmployeeId employeeId, LocalDate workDate, TimeClockEvent event,
                          EmployeeId recordedBy, String reason);

    /**
     * 打刻を取り消す（BR-09）。
     *
     * <p><strong>行を消さない。</strong> 取消行を追記することで表現する。
     * 打刻は労働時間の一次証拠であり、「元は何時だったか」を提示できる必要がある。
     *
     * @param targetId 取り消す打刻。同じ勤務日の同じ社員のものでなければならない
     */
    void revoke(EmployeeId employeeId, LocalDate workDate, TimeClockEventId targetId,
                EmployeeId recordedBy, String reason);

    /**
     * その勤務日の有効な打刻列。<strong>取り消された打刻は含まない。</strong>
     *
     * <p>打刻が 1 件も無い日は空の列を返す。休日や欠勤で正常に起こりうるので例外にしない。
     */
    TimeClockSequence findByWorkDate(EmployeeId employeeId, LocalDate workDate);

    /**
     * その勤務日の有効な打刻を、<strong>識別子つきで</strong>返す。
     *
     * <p>訂正申請が「どの打刻を取り消すか」を指すために使う。
     * 並びは {@link #findByWorkDate} と同じく時刻順。
     */
    java.util.List<RecordedTimeClockEvent> findRecordedByWorkDate(EmployeeId employeeId,
                                                                  LocalDate workDate);

    /**
     * まだ退勤していない勤務日。
     *
     * <p><strong>勤務日は打刻した暦日と一致しない</strong>（BR-03）。
     * 日をまたぐ勤務では、退勤・休憩の打刻を「出勤した日の勤務」に追記しなければならない。
     * 打刻した暦日をそのまま勤務日にすると、日跨ぎ勤務の退勤が
     * <strong>翌日の勤務として記録され、出勤の無い日に退勤だけが残る。</strong>
     *
     * @param onOrAfter この日以降の勤務日だけを見る。無期限に遡らせない
     * @return 開いている勤務日。無ければ空（次の打刻は出勤である）
     */
    java.util.Optional<LocalDate> findOpenWorkDate(EmployeeId employeeId, LocalDate onOrAfter);

    /**
     * その期間に有効な打刻がある勤務日。
     *
     * <p>月次清算の前に<strong>未計算の日が残っていないか</strong>を調べるために使う。
     * 打刻はあるのに日次勤怠が無い日は、退勤していないか計算に失敗した日である。
     * 1 日でも欠けたまま清算すると<strong>結果が過少になる。</strong>
     */
    java.util.List<LocalDate> findWorkDatesWithEvents(EmployeeId employeeId,
                                                      jp.co.sample.kintai.shared.domain
                                                              .DateRange period);
}
