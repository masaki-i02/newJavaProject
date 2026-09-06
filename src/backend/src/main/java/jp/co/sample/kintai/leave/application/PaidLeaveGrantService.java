package jp.co.sample.kintai.leave.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 年次有給休暇の付与（BR-14）。
 *
 * <p><strong>付与は行として実体化する。参照時に導出しない。</strong>
 * 実績が訂正されると過去の付与日数が黙って変わり、
 * 消化済みの日数を下回ることもありうる（ADR 0006）。
 *
 * <p><strong>契機はこのユースケース 1 本だけ。</strong>
 * 日次バッチも人事の API も同じメソッドを呼ぶ。手順を 2 か所に書かない（落とし穴 67）。
 * {@code GET} で付与しないのは、参照しただけで行が作られると
 * <strong>作った人も理由も残らない</strong>ためである。
 */
@Service
public class PaidLeaveGrantService {

    private static final Logger log = LoggerFactory.getLogger(PaidLeaveGrantService.class);

    private final PaidLeaveGrantRepository grants;
    private final EmployeeRepository employees;
    private final PaidLeaveGrantExecutor executor;
    private final EmployeeVisibility visibility;
    private final Clock clock;

    public PaidLeaveGrantService(PaidLeaveGrantRepository grants,
                                 EmployeeRepository employees,
                                 PaidLeaveGrantExecutor executor,
                                 EmployeeVisibility visibility,
                                 Clock clock) {
        this.grants = grants;
        this.employees = employees;
        this.executor = executor;
        this.visibility = visibility;
        this.clock = clock;
    }

    /**
     * 基準日までに到来した未処理の付与を、社員ごとに作る（BR-14）。
     *
     * <p><strong>当日ぶんだけを作らない。</strong> 到来済みで未処理のものをすべて作る。
     * バッチが動かなかった日があっても次の実行で追いつく（落とし穴 26）。
     *
     * <p><strong>社員ごとに別トランザクションで処理する</strong>（落とし穴 59）。
     * 1 人の計算が失敗しても他の 99 人の付与が巻き戻らない。
     */
    public GrantResult grantAsOf(Requester requester, LocalDate asOf) {
        // ★ 依頼そのものの不備は例外へ。全員を skipped にすると、
        //   人事は自分に権限が無いことに気づけない（落とし穴 60）
        requireHumanResources(requester);

        List<GrantResult.Granted> granted = new ArrayList<>();
        List<GrantResult.Withheld> withheld = new ArrayList<>();
        List<GrantResult.Skipped> skipped = new ArrayList<>();
        List<GrantResult.Failed> failed = new ArrayList<>();

        for (Employee employee : employees.findForDirectory(asOf, true)) {
            // ★ 1 人の失敗を他の 99 人に波及させない。
            //   ここで捕まえないと、例外がループを抜けて以降の社員が一件も処理されず、
            //   結果も返らないので誰も気づけない（落とし穴 60）
            try {
                collect(employee, asOf, granted, withheld, skipped);
            } catch (RuntimeException e) {
                log.warn("年次有給休暇の付与に失敗しました: 社員 {} / 基準日 {}",
                        employee.id().value(), asOf, e);
                failed.add(new GrantResult.Failed(employee.id(),
                        e.getClass().getSimpleName()));
            }
        }
        return new GrantResult(asOf, granted, withheld, skipped, failed);
    }

    private void collect(Employee employee, LocalDate asOf,
                         List<GrantResult.Granted> granted,
                         List<GrantResult.Withheld> withheld,
                         List<GrantResult.Skipped> skipped) {
        for (PaidLeaveGrantExecutor.Outcome outcome : executor.grantFor(employee, asOf)) {
            if (outcome.decision().isEmpty()) {
                skipped.add(new GrantResult.Skipped(employee.id(), outcome.grantedOn(),
                        GrantResult.Skipped.ALREADY_GRANTED));
                continue;
            }
            switch (outcome.decision().orElseThrow()) {
                case GrantDecision.Granted value -> granted.add(new GrantResult.Granted(
                        employee.id(), outcome.grantedOn(), value.days()));
                case GrantDecision.Withheld ignored -> withheld.add(
                        new GrantResult.Withheld(employee.id(), outcome.grantedOn(),
                                outcome.rate().orElseThrow()));
            }
        }
    }

    /**
     * 不付与だった付与を判定し直す（BR-14）。
     *
     * <p>使う場面は 2 つある。訂正申請（BR-09）で欠勤が出勤に直った場合と、
     * <strong>休業（労災・産前産後・育児介護）を出勤扱いとして申告する場合</strong>である。
     * 後者は休業を記録する機能が無い間の措置で、
     * <strong>打刻を足して実績を整える運用は採らない</strong>（要件 1.1）。
     * 働いていない日の労働時間が一次証拠として残り、割増賃金の計算に入ってしまう。
     */
    @Transactional
    public PaidLeaveGrant reassess(Requester requester, EmployeeId employeeId,
                                   LocalDate grantedOn, int deemedAttendedDays,
                                   String deemedReason) {
        requireHumanResources(requester);
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
        PaidLeaveGrant grant = grants.find(employeeId, grantedOn)
                .orElseThrow(() -> new GrantNotFoundException(employeeId, grantedOn));

        return executor.reassess(employee, grant, deemedAttendedDays, deemedReason);
    }

    /**
     * その社員の付与の履歴。<strong>閲覧範囲を確かめてから返す</strong>（要件 4.1）。
     *
     * <p>付与には出勤率と、出勤扱いの理由（「産前産後休業」など）が載る。
     * 絞らないと、誰でも他人の休業の事実を読めてしまう。
     */
    @Transactional(readOnly = true)
    public List<PaidLeaveGrant> grantsOf(Requester requester, EmployeeId employeeId) {
        if (!visibility.canView(requester, employeeId, LocalDate.now(clock))) {
            throw new AccessDeniedException();
        }
        return grants.findAll(employeeId);
    }

    /** 付与は会社の行為であり、本人・上長の操作ではない。 */
    private void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }

    /** 現在時刻の既定値。プレゼンテーション層で埋めない（AR-09）。 */
    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
