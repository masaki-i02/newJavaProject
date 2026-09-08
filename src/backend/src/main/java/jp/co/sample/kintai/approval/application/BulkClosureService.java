package jp.co.sample.kintai.approval.application;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 一括締め（BR-10）。
 *
 * <p><strong>{@link MonthlyAttendanceService} とは別のクラスにする。</strong>
 * 1 社員ずつ独立したトランザクションで処理したいが、
 * 同じクラスの中から呼ぶと Spring のプロキシを通らず、
 * <strong>{@code @Transactional} が効かないまま 1 つのトランザクションになる。</strong>
 * そうなると 1 件の失敗で 99 件の締めが巻き戻る。
 *
 * <p>このクラス自体には {@code @Transactional} を付けない。
 * 境界は {@code MonthlyAttendanceService.close} の側にある。
 */
@Service
public class BulkClosureService {

    private final MonthlyAttendanceService attendanceService;
    private final MonthlyAttendanceRepository attendances;
    private final EmployeeRepository employees;
    private final EmployeeVisibility visibility;
    private final java.time.Clock clock;

    public BulkClosureService(MonthlyAttendanceService attendanceService,
                              MonthlyAttendanceRepository attendances,
                              EmployeeRepository employees,
                              EmployeeVisibility visibility,
                              java.time.Clock clock) {
        this.attendanceService = attendanceService;
        this.attendances = attendances;
        this.employees = employees;
        this.visibility = visibility;
        this.clock = clock;
    }

    /**
     * まとめて締める。
     *
     * <p><strong>1 人でも締められない社員がいても、全体を失敗させない。</strong>
     * 100 人のうち 1 人が未承認なだけで 99 人の締めが止まると運用が回らない。
     *
     * <p>ただし<strong>社員ごとの事情と、依頼そのものの不備は分ける。</strong>
     * 人事でない・対象月がまだ終わっていない、は依頼そのものが成り立たないので
     * 例外のまま伝える。全員を {@code skipped} に並べても、
     * 人事は「自分に権限が無い」ことに気づけない。
     *
     * @param employeeIds 対象。<strong>空なら全社員</strong>
     */
    public BulkClosureResult closeAll(Requester requester, YearMonth month,
                                      Optional<List<EmployeeId>> employeeIds) {
        List<EmployeeId> targets = employeeIds.orElseGet(() -> allEmployeeIds(month));
        List<BulkClosureResult.Skipped> skipped = new ArrayList<>();
        int closed = 0;

        for (EmployeeId employeeId : targets) {
            // 締める前の状態を控える。締められなかった理由として返すため
            AttendanceState before = stateOf(employeeId, month);
            try {
                attendanceService.close(requester, employeeId, month,
                        attendances.currentVersion(employeeId, month));
                closed++;
            } catch (MonthlyAttendance.InvalidTransitionException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId, before,
                        reasonFor(before)));
            } catch (MonthlyAttendanceService.AttendanceNotFoundException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId,
                        AttendanceState.DRAFT, reasonFor(AttendanceState.DRAFT)));
            } catch (OptimisticLockingFailureException e) {
                skipped.add(new BulkClosureResult.Skipped(employeeId, before,
                        "他の利用者が先に更新しました"));
            }
        }
        return new BulkClosureResult(month, closed, skipped);
    }

    /**
     * 対象月に在籍した社員。<strong>退職者も含める。</strong>
     *
     * <p>3/31 退職の社員の 3 月分は締めなければならない。
     * 退職者を外すと、その月が永久に締まらない。
     *
     * <p><strong>{@code findAll(月末, true)} を使わない。</strong>
     * あの問いは {@code includeRetired} が真のとき基準日を無視して
     * <strong>全社員を返す</strong>ので、2 年前の退職者も来年入社の社員も
     * 対象に入る。締め自体は「提出されていません」として無害に
     * {@code skipped} へ落ちるが、<strong>結果が退職者で埋まって
     * 本当に対処すべき数名が埋もれる</strong>（落とし穴 60 が守ろうとしたのは
     * まさに「結果を見て次の行動が決まる」ことである）。
     * 問いは在籍期間との重なり（{@code findEmployedDuring}）である。
     */
    private List<EmployeeId> allEmployeeIds(YearMonth month) {
        return employees.findEmployedDuring(periodOf(month)).stream()
                .map(Employee::id).toList();
    }

    /** 暦月の半開区間。 */
    private static DateRange periodOf(YearMonth month) {
        return new DateRange(month.atDay(1), month.plusMonths(1).atDay(1));
    }

    /**
     * 締める前に人事が見る一覧（SC-08）。
     *
     * <p><strong>名簿を軸に列挙する。</strong> {@code monthly_attendances} を読んで
     * 返すと、行は提出時に初めて作られるので
     * <strong>最も必要な「未提出」が 1 人も出ない</strong>（落とし穴 120）。
     *
     * <p><strong>版を載せない</strong>（決定表「一覧に版を載せるか」）。
     * 締めは版を取らない {@link #closeAll} で行う。行ごとに版を引くと、
     * 社員数ぶんの問い合わせと認可判定が重複するうえ、
     * 「行が無い月」の版 0 が画面へ渡って落とし穴 57 の経路が一覧側から開く。
     *
     * <p><strong>{@code canClose} は {@code close} が実際に課す条件をそのまま写す。</strong>
     * 画面に {@code status === 'APPROVED'} と書かせると、
     * 人事であることと対象月が終わっていることが画面から落ちて、
     * 押せるのに 409 になるボタンが出る（決定表「次に何ができるか」）。
     *
     * <p>ロールで一律に拒まない。閲覧範囲で絞れば、承認者が呼んでも配下だけが返る
     * （決定表「一覧を返す API のロール」）。締められるかどうかは {@code canClose} が言う。
     */
    public List<ClosureStatus> closureStatuses(Requester requester, YearMonth month) {
        List<EmployeeId> visible = employees.findEmployedDuring(periodOf(month)).stream()
                .map(Employee::id)
                .filter(id -> visibility.canView(requester, id, month.atEndOfMonth()))
                .toList();
        // ★ 状態は一括で読む。1 件ずつ引くと社員数ぶんの問い合わせになる
        var states = attendanceService.statesOf(month, visible);
        boolean humanResources = requester.has(Role.HR);
        boolean finished = java.time.LocalDate.now(clock).isAfter(month.atEndOfMonth());
        return visible.stream()
                .map(id -> statusOf(id, states.get(id), humanResources, finished))
                .toList();
    }

    private static ClosureStatus statusOf(EmployeeId employeeId, AttendanceState state,
                                          boolean humanResources, boolean finished) {
        if (state != AttendanceState.APPROVED) {
            return new ClosureStatus(employeeId, state, false, reasonFor(state));
        }
        if (!finished) {
            return new ClosureStatus(employeeId, state, false,
                    "対象月がまだ終わっていません");
        }
        if (!humanResources) {
            return new ClosureStatus(employeeId, state, false,
                    "締められるのは人事だけです");
        }
        return new ClosureStatus(employeeId, state, true, null);
    }

    /**
     * 締める前の 1 行。
     *
     * <p><strong>{@code DRAFT} を「打刻が 1 件も無い」と読ませない。</strong>
     * 行が無いことが意味するのは下書きだけである（落とし穴 120）。
     * 打刻の有無は {@code attendance} が持つ別の事実で、この一覧には載せない。
     *
     * @param reason {@code canClose} が真なら {@code null}
     */
    public record ClosureStatus(EmployeeId employeeId, AttendanceState state,
                                boolean canClose, String reason) {

        public ClosureStatus {
            if (employeeId == null || state == null) {
                throw new IllegalArgumentException("社員と状態が要ります");
            }
            if (canClose != (reason == null)) {
                throw new IllegalArgumentException(
                        "締められない行には理由が、締められる行には理由が無いこと");
            }
        }
    }

    /** 行が無い月は下書き相当（API設計書 4 の 6）。 */
    private AttendanceState stateOf(EmployeeId employeeId, YearMonth month) {
        return attendances.find(employeeId, month)
                .map(attendance -> attendance.status().state())
                .orElse(AttendanceState.DRAFT);
    }

    /**
     * 締められなかった理由。
     *
     * <p><strong>網羅性検査つきの {@code switch} で書く。</strong>
     * 状態を足したときに、ここが「その他」に落ちて
     * 人事に理由の分からない結果が返るのを防ぐ。
     */
    private static String reasonFor(AttendanceState state) {
        return switch (state) {
            case DRAFT -> "提出されていません";
            case SUBMITTED -> "承認されていません";
            case CLOSED -> "すでに締め済みです";
            // 承認済なら締められるはずなので、ここへは来ない。
            // 来たとすれば読み取りと締めの間に状態が変わっている
            case APPROVED -> "他の利用者が先に更新しました";
        };
    }
}
