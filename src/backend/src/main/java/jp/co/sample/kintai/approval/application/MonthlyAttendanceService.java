package jp.co.sample.kintai.approval.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.approval.domain.ApprovalEvent;
import jp.co.sample.kintai.approval.domain.ApprovalEventKind;
import jp.co.sample.kintai.approval.domain.ApprovalEventRepository;
import jp.co.sample.kintai.approval.domain.Approver;
import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.approval.domain.ApproverPolicy;
import jp.co.sample.kintai.approval.domain.NotApproverException;
import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceId;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceRepository;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceStatus;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 月次勤怠の提出・承認・締め（BR-10 / BR-11）。
 *
 * <p><strong>状態遷移そのものは {@link MonthlyAttendance} が持つ。</strong>
 * ここが担うのは、遷移してよい人か・遷移してよい時期か・
 * 遷移したことを証跡に残すこと、の 3 つである。
 */
@Service
public class MonthlyAttendanceService {

    private final MonthlyAttendanceRepository attendances;
    private final ApprovalEventRepository events;
    private final ApproverPolicy approverPolicy;
    private final EmployeeRepository employees;
    private final EmployeeVisibility visibility;
    private final MonthlySettlementService settlements;
    private final Clock clock;

    public MonthlyAttendanceService(MonthlyAttendanceRepository attendances,
                                    ApprovalEventRepository events,
                                    ApproverPolicy approverPolicy,
                                    EmployeeRepository employees,
                                    EmployeeVisibility visibility,
                                    MonthlySettlementService settlements,
                                    Clock clock) {
        this.attendances = attendances;
        this.events = events;
        this.approverPolicy = approverPolicy;
        this.employees = employees;
        this.visibility = visibility;
        this.settlements = settlements;
        this.clock = clock;
    }

    /**
     * 提出する（BR-10）。
     *
     * <p>実行できるのは<strong>本人</strong>、または本人が在籍していない場合の
     * <strong>人事</strong>である。本人だけに限ると、
     * 3/31 退職の社員の 3 月分は提出できるのが 4 月以降なので
     * <strong>提出済に到達できず、承認も締めもできない。</strong>
     *
     * @param comment 代理提出の理由。本人の提出では空でよい
     */
    @Transactional
    public SubmissionResult submit(Requester requester, EmployeeId employeeId,
                                   YearMonth month, Optional<String> comment,
                                   long expectedVersion) {
        LocalDate today = LocalDate.now(clock);
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new AttendanceNotFoundException(employeeId, month));

        boolean self = requester.isSelf(employeeId);
        boolean proxy = !self && requester.has(Role.HR) && !employee.isActiveOn(today);
        if (!self && !proxy) {
            // 在籍している社員の勤怠を人事が代理提出することは認めない。
            // 本人が提出できる状態なら、本人が提出する
            throw new AccessDeniedException();
        }

        // ★ 代理提出は理由が必須。DB の approval_events_proxy_reason_check に
        //   任せると、制約違反が理由の載らない応答になって人事は直しようが無い
        //   （落とし穴 66・105）。本人の提出では要らないので @NotBlank では課せない
        if (proxy && comment.map(String::isBlank).orElse(true)) {
            throw new ProxySubmissionReasonRequiredException(employeeId, month);
        }

        requireMonthFinished(month, today);

        // ★ 未計算の勤務日が残っていないかを確かめる。
        //   判定は attendance が持つ（落とし穴 67）。確かめるのは
        //   「打刻があるのに日次勤怠が無い日」であって「打刻が無い日」ではない。
        //   欠勤の日を未確定に数えると、1 日でも休んだ月を永久に提出できなくなる
        settlements.requireCalculable(employeeId, month);

        // ★ 提出を契機に月次清算を計算し直す（月次清算 API設計書 3.1）。
        //   ここで行わないと、承認者は日次だけが直った古い月次を見て承認することになる。
        //   誰かが画面で見ている値ではないので、版は突き合わせない
        settlements.settle(employeeId, month);

        MonthlyAttendance current = loadOrDraft(employeeId, month);
        MonthlyAttendance next = current.submit(requester.employeeId(),
                LocalDateTime.now(clock));
        MonthlyAttendance submitted = apply(current, next,
                proxy ? ApprovalEventKind.PROXY_SUBMIT : ApprovalEventKind.SUBMIT,
                requester.employeeId(), comment, OptionalLong.of(expectedVersion));

        // ★ 提出を止めない。承認済みの年休の日に出勤していたことを警告として返す。
        //   取り消せるのは締め前だけなので、締め前に必ず通るここで気づかせる（落とし穴 97）
        return new SubmissionResult(submitted,
                settlements.workedOnPaidLeaveDates(employeeId, month));
    }

    /** 承認する（BR-11 の承認者）。 */
    @Transactional
    public MonthlyAttendance approve(Requester requester, EmployeeId employeeId,
                                     YearMonth month, long expectedVersion) {
        // ★ 集約を読むより先に「誰の依頼か」を見る。
        //   あとに置くと、行が無ければ 404・あれば 403 になり、
        //   承認者でない者が「その社員がその月を提出したか」を判別できる
        //   （CLAUDE.md「依頼者の検査の順序」）
        // ★ 自己承認を承認者の判定より先に見る（BR-11 の 4）。
        //   ApproverPolicy は本人を承認者から外すので、あとに置くと not-approver が返り、
        //   自己承認という事実が利用者に伝わらない。集約の検査は
        //   アプリケーション層を通さない経路のために残す（落とし穴 58）
        if (requester.isSelf(employeeId)) {
            throw new MonthlyAttendance.SelfApprovalException(employeeId, month);
        }
        requireApprover(requester, employeeId, month);
        MonthlyAttendance current = load(employeeId, month);
        MonthlyAttendance next = current.approve(requester.employeeId(),
                LocalDateTime.now(clock));
        return apply(current, next, ApprovalEventKind.APPROVE, requester.employeeId(),
                Optional.empty(), OptionalLong.of(expectedVersion));
    }

    /** 差し戻す（BR-11 の承認者）。<strong>理由が必須。</strong> */
    @Transactional
    public MonthlyAttendance reject(Requester requester, EmployeeId employeeId,
                                    YearMonth month, String reason,
                                    long expectedVersion) {
        // ★ 承認と同じ順序で並べる（自己 → 承認者 → 業務）。集約を読むのはそのあと。
        //   後ろに置くと ApproverPolicy が本人を承認者から外して not-approver が返り、
        //   自己決裁という事実が伝わらない
        if (requester.isSelf(employeeId)) {
            throw new MonthlyAttendance.SelfApprovalException(employeeId, month);
        }
        requireApprover(requester, employeeId, month);
        MonthlyAttendance current = load(employeeId, month);
        return apply(current, current.reject(requester.employeeId()),
                ApprovalEventKind.REJECT,
                requester.employeeId(), Optional.ofNullable(reason),
                OptionalLong.of(expectedVersion));
    }

    /** 締める（人事）。 */
    @Transactional
    public MonthlyAttendance close(Requester requester, EmployeeId employeeId,
                                   YearMonth month, long expectedVersion) {
        requireHumanResources(requester);
        MonthlyAttendance current = load(employeeId, month);
        requireMonthFinished(month, LocalDate.now(clock));
        MonthlyAttendance next = current.close(requester.employeeId(),
                LocalDateTime.now(clock));
        return apply(current, next, ApprovalEventKind.CLOSE, requester.employeeId(),
                Optional.empty(), OptionalLong.of(expectedVersion));
    }

    /**
     * 承認を取り消す（人事）。<strong>理由が必須。</strong>
     *
     * <p>承認後・締め前に誤りが見つかることは実務で起きる。
     * 戻す手段が無いと、締めてしまうか DB を直接触るしかなくなる。
     */
    @Transactional
    public MonthlyAttendance revokeApproval(Requester requester, EmployeeId employeeId,
                                            YearMonth month, String reason,
                                            long expectedVersion) {
        requireHumanResources(requester);
        MonthlyAttendance current = load(employeeId, month);
        return apply(current, current.revokeApproval(), ApprovalEventKind.REVOKE_APPROVAL,
                requester.employeeId(), Optional.ofNullable(reason),
                OptionalLong.of(expectedVersion));
    }

    /**
     * 訂正の承認により下書きへ戻す。
     *
     * <p><strong>提出済でなければ何もしない。</strong>
     * 訂正はどの状態でも承認されうるが、戻すべき状態は提出済だけである。
     * 例外にすると、下書きの月の訂正を承認できなくなる。
     */
    @Transactional
    public void revertByCorrection(EmployeeId employeeId, YearMonth month,
                                   EmployeeId actor, UUID correctionRequestId) {
        Optional<MonthlyAttendance> found = attendances.find(employeeId, month);
        if (found.isEmpty()
                || !(found.get().status() instanceof MonthlyAttendanceStatus.Submitted)) {
            return;
        }
        MonthlyAttendance current = found.get();
        apply(current, current.revertByCorrection(), ApprovalEventKind.REVERT_BY_CORRECTION,
                actor, Optional.of("打刻訂正の承認による自動差戻し（申請 ID: %s）"
                        .formatted(correctionRequestId)), OptionalLong.empty());
    }

    /**
     * 年休の承認・取消により下書きへ戻す（BR-16）。
     *
     * <p><strong>提出済でなければ何もしない。</strong>
     * 年休は下書きの月でも承認されうるが、戻すべき状態は提出済だけである。
     *
     * <p><strong>申請中の取下げでは呼ばない。</strong>
     * 承認されていない年休は所定総を変えていないので、戻す理由が無い。
     * 呼ぶと、取り下げただけで承認者の待ち行列からその月が消え、
     * しかも {@code REVERT_BY_LEAVE} という嘘の証跡が残る（CLAUDE.md 落とし穴 95）。
     */
    @Transactional
    public void revertByLeave(EmployeeId employeeId, YearMonth month, EmployeeId actor,
                              UUID leaveRequestId) {
        Optional<MonthlyAttendance> found = attendances.find(employeeId, month);
        if (found.isEmpty()
                || !(found.get().status() instanceof MonthlyAttendanceStatus.Submitted)) {
            return;
        }
        MonthlyAttendance current = found.get();
        apply(current, current.revertByLeave(), ApprovalEventKind.REVERT_BY_LEAVE,
                actor, Optional.of("年次有給休暇の承認・取消による自動差戻し（申請 ID: %s）"
                        .formatted(leaveRequestId)), OptionalLong.empty());
    }

    /** その月の状態。閲覧範囲を確かめてから返す。 */
    @Transactional(readOnly = true)
    public Optional<MonthlyAttendance> find(Requester requester, EmployeeId employeeId,
                                            YearMonth month) {
        if (!visibility.canView(requester, employeeId, month.atEndOfMonth())) {
            throw new AccessDeniedException();
        }
        return attendances.find(employeeId, month);
    }

    /**
     * その月の状態（判別値）。
     *
     * <p>訂正の承認が「下書きに戻った」ことを応答へ載せるために使う。
     * <strong>行が無い月は下書き相当。</strong>
     */
    /**
     * その月の状態を社員ごとにまとめて返す（BR-18）。
     *
     * <p><strong>行が無い社員も「下書き」として返す。</strong>
     * {@link #stateOf(EmployeeId, YearMonth)} と同じ既定である。
     * ただし<strong>「行が無い」と「1 日も打刻が無い」は別の事実</strong>なので、
     * 打刻の有無は {@code attendance} に問うこと（落とし穴 120）。
     *
     * <p>1 件ずつ引くと社員数ぶんの問い合わせになるので、一括で読む。
     */
    @Transactional(readOnly = true)
    public Map<EmployeeId, AttendanceState> statesOf(YearMonth month,
                                                     Collection<EmployeeId> employeeIds) {
        Map<EmployeeId, AttendanceState> stored = attendances.findStates(month);
        Map<EmployeeId, AttendanceState> states = new LinkedHashMap<>();
        for (EmployeeId employeeId : employeeIds) {
            states.put(employeeId, stored.getOrDefault(employeeId, AttendanceState.DRAFT));
        }
        return java.util.Collections.unmodifiableMap(states);
    }

    @Transactional(readOnly = true)
    public AttendanceState stateOf(EmployeeId employeeId, YearMonth month) {
        return attendances.find(employeeId, month)
                .map(attendance -> attendance.status().state())
                .orElse(AttendanceState.DRAFT);
    }

    /**
     * 現在の版（API設計書 1.1）。
     *
     * <p><strong>取得する経路が無いと、利用者は版を送れない。</strong>
     * 行が無い月は 0 を返す。提出が最初の遷移になる。
     */
    @Transactional(readOnly = true)
    public long currentVersion(Requester requester, EmployeeId employeeId,
                               YearMonth month) {
        if (!visibility.canView(requester, employeeId, month.atEndOfMonth())) {
            throw new AccessDeniedException();
        }
        return attendances.currentVersion(employeeId, month);
    }

    /**
     * 承認待ちの一覧。
     *
     * <p><strong>見てよい社員のぶんだけ返す。</strong>
     * 承認者には配下部署の社員、人事には全社員。
     * 絞り込みをリポジトリではなくここで行うのは、
     * 判定が組織の状態に依存する業務判断だからである。
     */
    @Transactional(readOnly = true)
    public List<MonthlyAttendance> findPendingApproval(Requester requester,
                                                       YearMonth month) {
        return attendances.findSubmitted(month).stream()
                .filter(attendance -> visibility.canView(requester,
                        attendance.employeeId(), month.atEndOfMonth()))
                .toList();
    }

    /**
     * 画面が「次に何ができるか」を決めるための一式
     * （[05 API設計書 2.1](../../../../../../../../doc/02_詳細設計/05_申請承認と締め/API設計書.md)）。
     *
     * <p><strong>状態機械と BR-11 を画面に複製させない。</strong>
     * {@code canSubmit} / {@code canApprove} は
     * <strong>ログイン中の利用者が実行できるか</strong>をサーバが判断して返す。
     * 画面が {@code status === 'DRAFT'} のような分岐を書くと、
     * 提出の条件を変えたときに 2 か所を直すことになる。
     *
     * <p>判定は {@link #submit} / {@link #approve} が実際に課す条件をそのまま写す。
     * 「押せるのに実行すると弾かれる」ボタンを出さないためである。
     */
    @Transactional(readOnly = true)
    public MonthlyAttendanceView view(Requester requester, EmployeeId employeeId,
                                      YearMonth month) {
        LocalDate today = LocalDate.now(clock);
        MonthlyAttendance attendance = find(requester, employeeId, month)
                .orElseGet(() -> MonthlyAttendance.draft(new MonthlyAttendanceId(UUID.randomUUID()),
                        employeeId, month));
        long version = currentVersion(requester, employeeId, month);
        Approver approver = approverOf(requester, employeeId, month);
        List<ApprovalEvent> history = events.findBy(attendance.id());

        return new MonthlyAttendanceView(employeeId, month, attendance.status(), version,
                canSubmit(requester, employeeId, month, attendance, today),
                canApprove(requester, employeeId, attendance, approver),
                approver, history);
    }

    /**
     * 本人（または退職者を代理する人事）が、いま提出できるか。
     *
     * <p>{@link #submit} が課す条件のうち、<strong>ボタンで避けられるもの</strong>を写す。
     * 楽観ロックの版だけは押した瞬間にしか判定できないので含めない。
     */
    private boolean canSubmit(Requester requester, EmployeeId employeeId, YearMonth month,
                              MonthlyAttendance attendance, LocalDate today) {
        if (attendance.status().state() != AttendanceState.DRAFT) {
            return false;
        }
        if (!isMonthFinished(month, today)) {
            return false;
        }
        boolean self = requester.isSelf(employeeId);
        boolean proxy = !self && requester.has(Role.HR) && employees.findById(employeeId)
                .map(employee -> !employee.isActiveOn(today)).orElse(false);
        if (!self && !proxy) {
            return false;
        }
        // ★ 「打刻があるのに日次勤怠が無い日」が残っていないか。
        //   判定は attendance が持つ（落とし穴 67）
        return settlements.isCalculable(employeeId, month);
    }

    /**
     * ログイン中の利用者が、いま承認できるか。
     *
     * <p>自己承認の禁止（BR-11 の 4）を承認者の判定より先に見る。
     * {@link #approve} と同じ順序にそろえないと、
     * 本人には「承認できない理由」が承認者不在として伝わる（落とし穴 110）。
     */
    private boolean canApprove(Requester requester, EmployeeId employeeId,
                               MonthlyAttendance attendance, Approver approver) {
        if (attendance.status().state() != AttendanceState.SUBMITTED) {
            return false;
        }
        if (requester.isSelf(employeeId)) {
            return false;
        }
        return approver.isApprovedBy(requester.employeeId(), requester.has(Role.HR));
    }

    /**
     * 画面へ返す一式。
     *
     * @param status   状態。<strong>提出者・承認者・締めた人と日時を型として持つ</strong>
     * @param version  更新系のリクエストで必須。<strong>取得する経路はここだけ</strong>
     * @param approver 誰が承認するか。遡った経路つき（BR-11）
     * @param history  全遷移。差戻しの理由を含む
     */
    public record MonthlyAttendanceView(EmployeeId employeeId, YearMonth month,
                                        MonthlyAttendanceStatus status, long version,
                                        boolean canSubmit, boolean canApprove,
                                        Approver approver, List<ApprovalEvent> history) {
    }

    /** 承認者を答える（画面の「誰に承認してもらうか」の表示に使う）。 */
    @Transactional(readOnly = true)
    public Approver approverOf(Requester requester, EmployeeId employeeId,
                               YearMonth month) {
        if (!visibility.canView(requester, employeeId, month.atEndOfMonth())) {
            throw new AccessDeniedException();
        }
        return approverPolicy.resolve(employeeId, month, LocalDate.now(clock));
    }

    /**
     * 遷移を保存し、証跡へ残す。<strong>2 つを別々の経路にしない。</strong>
     *
     * @param expectedVersion 突き合わせる版。
     *                        <strong>空なら突き合わせない</strong>（システム契機の遷移）
     */
    private MonthlyAttendance apply(MonthlyAttendance current, MonthlyAttendance next,
                                    ApprovalEventKind kind, EmployeeId actor,
                                    Optional<String> comment,
                                    OptionalLong expectedVersion) {
        if (expectedVersion.isPresent()) {
            attendances.save(next, expectedVersion.getAsLong());
        } else {
            attendances.save(next);
        }
        events.append(new ApprovalEvent(next.id(), current.status().state(),
                next.status().state(),
                kind, actor, comment, LocalDateTime.now(clock)));
        return next;
    }

    private MonthlyAttendance load(EmployeeId employeeId, YearMonth month) {
        return attendances.find(employeeId, month)
                .orElseThrow(() -> new AttendanceNotFoundException(employeeId, month));
    }

    private MonthlyAttendance loadOrDraft(EmployeeId employeeId, YearMonth month) {
        return attendances.find(employeeId, month).orElseGet(() -> MonthlyAttendance.draft(
                new MonthlyAttendanceId(UUID.randomUUID()), employeeId, month));
    }

    /**
     * 対象月の末日が到来しているか。
     *
     * <p><strong>これを見ないと、月初でも提出・承認・締めが通る。</strong>
     * 勤務日がまだ来ていないので「未確定の日」が空になるためで、
     * 締めてしまうと戻す手段が無い。
     *
     * <p><strong>公開する。</strong> 給与連携（BR-18）も同じ条件を課すが、
     * 述語を写すと片方だけが古くなる（CLAUDE.md 落とし穴 67）。
     * 例外の型を共有するだけでは、判定式が 2 か所に残る。
     */
    public void requireMonthFinished(YearMonth month) {
        requireMonthFinished(month, LocalDate.now(clock));
    }

    /**
     * 対象月の末日が到来しているか。
     *
     * <p><strong>公開する。</strong> 同じ述語を写すと片方だけが古くなる（落とし穴 67）。
     * 実際、提出の可否（{@code canSubmit}）と一括締めの一覧
     * （{@code BulkClosureService}）がそれぞれ手で書いていた。
     * 一括締めの側を直し忘れると、<strong>一覧では「締められます」と出るのに
     * 実行すると全件 skip</strong> になり、人事は理由に辿り着けない。
     */
    public boolean isMonthFinished(YearMonth month) {
        return isMonthFinished(month, LocalDate.now(clock));
    }

    private boolean isMonthFinished(YearMonth month, LocalDate today) {
        return today.isAfter(month.atEndOfMonth());
    }

    private void requireMonthFinished(YearMonth month, LocalDate today) {
        if (!isMonthFinished(month, today)) {
            throw new MonthNotFinishedException(month);
        }
    }

    private void requireHumanResources(Requester requester) {
        if (!requester.has(Role.HR)) {
            throw new AccessDeniedException();
        }
    }

    /**
     * 承認してよい人か（BR-11）。
     *
     * <p><strong>人事はいつでも承認できるわけではない。</strong>
     * BR-11 の 5 は「遡っても承認者が得られない場合」に限って人事へ回す。
     * 無条件の承認権を与えると、自己承認の禁止（BR-11 の 4）を含む
     * 1〜4 のすべてを迂回する経路ができる。
     */
    private void requireApprover(Requester requester, EmployeeId employeeId,
                                 YearMonth month) {
        Approver approver = approverPolicy.resolve(employeeId, month,
                LocalDate.now(clock));
        if (!approver.isApprovedBy(requester.employeeId(), requester.has(Role.HR))) {
            throw new NotApproverException();
        }
    }

    /** 月次勤怠が無い。 */
    public static final class AttendanceNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        AttendanceNotFoundException(EmployeeId employeeId, YearMonth month) {
            super("月次勤怠が見つかりません: 社員 %s / 対象月 %s"
                    .formatted(employeeId.value(), month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "月次勤怠が見つかりません";
        }
    }

    /**
     * 対象月の末日がまだ到来していない。
     *
     * <p><strong>給与連携（BR-18）も同じ型を使う。</strong>
     * 「対象月が終わっていない」は締めでも出力でも同じ業務ルールであり、
     * コンテキストごとに違うエラー型や HTTP を割り当てると、
     * 画面が条件ごとに分岐を書き分けることになる（CLAUDE.md 落とし穴 110・124）。
     */
    public static final class MonthNotFinishedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        public MonthNotFinishedException(YearMonth month) {
            super("対象月がまだ終わっていません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-not-finished";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "対象月がまだ終わっていません";
        }
    }


    /**
     * 代理提出に理由が付いていない。
     *
     * <p>代理提出は<strong>本人が出していない提出</strong>なので、
     * なぜ人事が代わりに出したのかが証跡に残らなければならない
     * （{@code approval_events_proxy_reason_check}）。
     *
     * <p><strong>DB の制約に任せない。</strong>
     * 制約違反は利用者に説明できない（落とし穴 66）。
     */
    public static final class ProxySubmissionReasonRequiredException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        ProxySubmissionReasonRequiredException(EmployeeId employeeId, YearMonth month) {
            super("代理提出には理由が必要です: 対象社員 %s / 対象月 %s"
                    .formatted(employeeId.value(), month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:proxy-submission-reason-required";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "代理提出には理由が必要です";
        }
    }
}
