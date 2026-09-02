package jp.co.sample.kintai.attendance.infrastructure;

import static jp.co.sample.kintai.support.ConstraintAssertions.accepted;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedBy;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedWithMessage;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 打刻の制約（IT-ATT-01・06〜14・20〜21）。
 *
 * <p>対応する設計は
 * {@code doc/02_詳細設計/03_勤怠_打刻と日次集計/DB設計書.md} の 6 章。
 */
@DisplayName("打刻の制約")
class TimeClockConstraintTest extends IntegrationTestBase {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 4, 7);

    private Fixtures fixtures;
    private UUID taro;
    private UUID jiro;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        taro = fixtures.employee("E0001", LocalDate.of(2026, 1, 1));
        jiro = fixtures.employee("E0002", LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("IT-ATT-01 打刻が work_date に対応するパーティションへ入る")
    void routedToPartition() {
        UUID id = fixtures.punch(taro, WORK_DATE, "CLOCK_IN", "2026-04-07 13:00:00+09");

        assertThat(jdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM time_clock_events WHERE id = ?",
                String.class, id)).isEqualTo("time_clock_events_2026");
    }

    @Nested
    @DisplayName("打刻時刻と勤務日の整合")
    class OccurredAt {

        @Test
        @DisplayName("IT-ATT-14 勤務日の翌日の打刻は受け入れられる（日をまたぐ勤務）")
        void nextDayIsAccepted() {
            accepted(() -> fixtures.punch(taro, WORK_DATE, "CLOCK_OUT",
                    "2026-04-08 03:00:00+09"));
        }

        @Test
        @DisplayName("IT-ATT-13 勤務日から 1 年離れた打刻は拒否される")
        void farFromWorkDateIsRejected() {
            rejectedWithMessage("打刻時刻が勤務日から離れすぎています",
                    () -> fixtures.punch(taro, WORK_DATE, "CLOCK_IN",
                            "2027-12-31 09:00:00+09"));
        }
    }

    @Nested
    @DisplayName("取消（REVOCATION）")
    class Revocation {

        private UUID clockOut;

        @BeforeEach
        void setUpPunches() {
            fixtures.punch(taro, WORK_DATE, "CLOCK_IN", "2026-04-07 13:00:00+09");
            clockOut = fixtures.punch(taro, WORK_DATE, "CLOCK_OUT", "2026-04-08 03:00:00+09");
        }

        @Test
        @DisplayName("IT-ATT-06 取消行に対象を設定しないと拒否される")
        void revocationWithoutTarget() {
            rejectedBy("time_clock_events_revocation_check", () -> jdbc.update("""
                    INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                            event_type, occurred_at, reason, recorded_by)
                    VALUES (?, ?, ?, 'REVOCATION', 'CLOCK_OUT',
                            TIMESTAMPTZ '2026-04-08 03:00:00+09', '誤り', ?)
                    """, Fixtures.id(), WORK_DATE, taro, taro));
        }

        @Test
        @DisplayName("IT-ATT-07 通常の打刻に取消対象を設定すると拒否される")
        void entryWithTarget() {
            rejectedBy("time_clock_events_revocation_check", () -> jdbc.update("""
                    INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                            event_type, occurred_at, revokes_event_id, recorded_by)
                    VALUES (?, ?, ?, 'ENTRY', 'CLOCK_OUT',
                            TIMESTAMPTZ '2026-04-08 03:00:00+09', ?, ?)
                    """, Fixtures.id(), WORK_DATE, taro, clockOut, taro));
        }

        @Test
        @DisplayName("IT-ATT-08 訂正で追記された打刻に理由が無いと拒否される")
        void correctionEntryWithoutReason() {
            rejectedBy("time_clock_events_revocation_check", () -> jdbc.update("""
                    INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                            event_type, occurred_at, source, recorded_by)
                    VALUES (?, ?, ?, 'ENTRY', 'BREAK_START',
                            TIMESTAMPTZ '2026-04-07 18:00:00+09', 'CORRECTION', ?)
                    """, Fixtures.id(), WORK_DATE, taro, taro));
        }

        /**
         * 第 1 版の外部キーは {@code (work_date, revokes_event_id)} しか見ておらず、
         * <strong>他人の打刻を自分名義で取り消せた。</strong>
         * 有効打刻のクエリから消えるため、労働時間が失われる。
         */
        @Test
        @DisplayName("IT-ATT-10 他人の打刻を取り消す行は拒否される")
        void cannotRevokeSomeoneElsesPunch() {
            rejectedBy("time_clock_events_revokes_fk",
                    () -> fixtures.revoke(jiro, WORK_DATE, "CLOCK_OUT",
                            "2026-04-08 03:00:00+09", clockOut, "乗っ取り"));
        }

        @Test
        @DisplayName("IT-ATT-12 取消行の打刻種別が対象と違うと拒否される")
        void eventTypeMustMatchTarget() {
            rejectedWithMessage("取消行の打刻種別が対象と一致しません",
                    () -> fixtures.revoke(taro, WORK_DATE, "BREAK_END",
                            "2026-04-08 03:00:00+09", clockOut, "種別違い"));
        }

        @Test
        @DisplayName("IT-ATT-11 取消行を取り消すことはできない")
        void revocationCannotBeRevoked() {
            UUID revocation = fixtures.revoke(taro, WORK_DATE, "CLOCK_OUT",
                    "2026-04-08 03:00:00+09", clockOut, "退勤時刻の誤り");
            rejectedWithMessage("取消できるのは通常の打刻だけです",
                    () -> fixtures.revoke(taro, WORK_DATE, "CLOCK_OUT",
                            "2026-04-08 03:00:00+09", revocation, "取消の取消"));
        }

        @Test
        @DisplayName("IT-ATT-09 同じ打刻を二重に取り消すことはできない")
        void cannotRevokeTwice() {
            fixtures.revoke(taro, WORK_DATE, "CLOCK_OUT", "2026-04-08 03:00:00+09",
                    clockOut, "退勤時刻の誤り");
            rejectedBy("time_clock_events_2026_work_date_revokes_event_id_idx",
                    () -> fixtures.revoke(taro, WORK_DATE, "CLOCK_OUT",
                            "2026-04-08 03:00:00+09", clockOut, "二重取消"));
        }

        @Test
        @DisplayName("IT-ATT-20 有効な打刻のクエリが、取り消された打刻を除く")
        void effectivePunchesExcludeRevoked() {
            fixtures.revoke(taro, WORK_DATE, "CLOCK_OUT", "2026-04-08 03:00:00+09",
                    clockOut, "退勤時刻の誤り");
            jdbc.update("""
                    INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                            event_type, occurred_at, source, reason, recorded_by)
                    VALUES (?, ?, ?, 'ENTRY', 'CLOCK_OUT',
                            TIMESTAMPTZ '2026-04-08 04:00:00+09', 'CORRECTION',
                            '正しい退勤時刻', ?)
                    """, Fixtures.id(), WORK_DATE, taro, taro);

            assertThat(effectivePunches(taro, WORK_DATE))
                    .containsExactly("CLOCK_IN@13:00", "CLOCK_OUT@04:00");
        }
    }

    /**
     * 第 1 版は前日 1 日しか見ておらず、
     * <strong>金曜に打ち忘れて月曜に出勤したケースを取りこぼしていた。</strong>
     */
    @Test
    @DisplayName("IT-ATT-21 未退勤の勤務日を、締めていない全期間から探せる")
    void findsOpenWorkDateAcrossTheWeekend() {
        fixtures.punch(taro, LocalDate.of(2026, 4, 3), "CLOCK_IN", "2026-04-03 09:00:00+09");
        fixtures.punch(taro, LocalDate.of(2026, 4, 6), "CLOCK_IN", "2026-04-06 09:00:00+09");
        fixtures.punch(taro, LocalDate.of(2026, 4, 6), "CLOCK_OUT", "2026-04-06 18:00:00+09");

        List<LocalDate> open = jdbc.queryForList("""
                SELECT e.work_date
                  FROM time_clock_events e
                 WHERE e.employee_id = ?
                   AND e.work_date >= ? AND e.work_date < ?
                   AND e.entry_type = 'ENTRY'
                   AND NOT EXISTS (SELECT 1 FROM time_clock_events r
                                    WHERE r.work_date = e.work_date
                                      AND r.employee_id = e.employee_id
                                      AND r.entry_type = 'REVOCATION'
                                      AND r.revokes_event_id = e.id)
                 GROUP BY e.work_date
                HAVING sum(CASE WHEN e.event_type = 'CLOCK_OUT' THEN 1 ELSE 0 END) = 0
                 ORDER BY e.work_date
                """, LocalDate.class, taro, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1));

        assertThat(open).containsExactly(LocalDate.of(2026, 4, 3));
    }

    /** DB設計書 4.1 のクエリ。 */
    private List<String> effectivePunches(UUID employeeId, LocalDate workDate) {
        return jdbc.query("""
                SELECT e.event_type,
                       to_char(e.occurred_at AT TIME ZONE 'Asia/Tokyo', 'HH24:MI') AS at
                  FROM time_clock_events e
                 WHERE e.employee_id = ? AND e.work_date = ? AND e.entry_type = 'ENTRY'
                   AND NOT EXISTS (SELECT 1 FROM time_clock_events r
                                    WHERE r.work_date = e.work_date
                                      AND r.employee_id = e.employee_id
                                      AND r.entry_type = 'REVOCATION'
                                      AND r.revokes_event_id = e.id)
                 ORDER BY e.occurred_at
                """, (rs, i) -> rs.getString("event_type") + "@" + rs.getString("at"),
                employeeId, workDate);
    }
}
