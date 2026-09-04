package jp.co.sample.kintai.approval.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.approval.domain.Approver;
import jp.co.sample.kintai.approval.domain.ApproverPolicy;
import jp.co.sample.kintai.approval.domain.CorrectionItem;
import jp.co.sample.kintai.approval.domain.CorrectionRequest;
import jp.co.sample.kintai.approval.domain.CorrectionRequestId;
import jp.co.sample.kintai.approval.domain.CorrectionRequestRepository;
import jp.co.sample.kintai.attendance.application.TimeClockService;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.domain.RecordedTimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockSequenceException;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DetailedDomainException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 打刻の訂正申請（BR-09）。
 *
 * <p>打刻は<strong>追記しかしない。</strong>
 * 訂正は「取消行を追記する」ことと「新しい打刻を追記する」ことの組み合わせで表す。
 * これにより「元は何時だったか」が労務トラブル時に提示できる。
 *
 * <p>承認は 5 つの更新を<strong>1 トランザクション</strong>で行う。
 * 途中で切れると、打刻だけが直って日次・月次・月次勤怠の状態が食い違う。
 */
@Service
public class CorrectionRequestService {

    private final CorrectionRequestRepository requests;
    private final TimeClockEventRepository timeClocks;
    private final TimeClockService timeClockService;
    private final MonthlySettlementService settlements;
    private final MonthlyAttendanceService monthlyAttendances;
    private final ApproverPolicy approverPolicy;
    private final MonthClosureQuery monthClosure;
    private final EmployeeVisibility visibility;
    private final Clock clock;

    public CorrectionRequestService(CorrectionRequestRepository requests,
                                    TimeClockEventRepository timeClocks,
                                    TimeClockService timeClockService,
                                    MonthlySettlementService settlements,
                                    MonthlyAttendanceService monthlyAttendances,
                                    ApproverPolicy approverPolicy,
                                    MonthClosureQuery monthClosure,
                                    EmployeeVisibility visibility,
                                    Clock clock) {
        this.requests = requests;
        this.timeClocks = timeClocks;
        this.timeClockService = timeClockService;
        this.settlements = settlements;
        this.monthlyAttendances = monthlyAttendances;
        this.approverPolicy = approverPolicy;
        this.monthClosure = monthClosure;
        this.visibility = visibility;
        this.clock = clock;
    }

    /**
     * 訂正を申請する（本人）。
     *
     * <p><strong>承認を待たずに、この時点で検証する。</strong>
     * 承認者が承認したあとで「その訂正を適用すると打刻列が壊れる」と分かるのでは遅い。
     */
    @Transactional
    public CorrectionRequest request(Requester requester, EmployeeId employeeId,
                                     LocalDate workDate, List<CorrectionItem> items,
                                     String reason) {
        if (!requester.isSelf(employeeId)) {
            // 訂正は本人の意思表示である。人事でも代理では出せない
            throw new AccessDeniedException();
        }
        requireMonthAcceptsCorrection(employeeId, workDate);
        requests.findPending(employeeId, workDate).ifPresent(pending -> {
            throw new PendingCorrectionExistsException(workDate);
        });

        CorrectionRequest request = CorrectionRequest.submit(
                new CorrectionRequestId(java.util.UUID.randomUUID()), employeeId,
                workDate, items, reason, LocalDateTime.now(clock));

        List<RecordedTimeClockEvent> current =
                timeClocks.findRecordedByWorkDate(employeeId, workDate);
        List<TimeClockEventId> missing = request.missingTargets(current);
        if (!missing.isEmpty()) {
            throw new CorrectionTargetNotFoundException(missing);
        }
        requireAppliesCleanly(request, current);

        requests.insert(request);
        return request;
    }

    /**
     * 訂正を承認する（BR-11 の承認者）。
     *
     * <p>5 つの更新を 1 トランザクションで行う。
     *
     * <ol>
     *   <li>取消行を追記する</li>
     *   <li>新しい打刻を追記する</li>
     *   <li>日次勤怠を計算し直す</li>
     *   <li><strong>月次清算を計算し直す</strong></li>
     *   <li><strong>月次勤怠を下書きへ戻す</strong></li>
     * </ol>
     *
     * <p>4 を忘れると日次だけが直って月次の時間外が古いままになる。
     * 5 を忘れると提出済みのまま内容だけが変わり、
     * 承認者が確認した内容と実際に確定される内容が食い違う。
     */
    @Transactional
    public CorrectionResult approve(Requester requester, CorrectionRequestId id,
                                    long expectedVersion) {
        CorrectionRequest request = load(id);
        requireApprover(requester, request);
        requireMonthAcceptsCorrection(request.employeeId(), request.workDate());

        // 申請から承認までの間に打刻が変わっていることがある。もう一度確かめる
        List<RecordedTimeClockEvent> current = timeClocks
                .findRecordedByWorkDate(request.employeeId(), request.workDate());
        List<TimeClockEventId> missing = request.missingTargets(current);
        if (!missing.isEmpty()) {
            throw new CorrectionTargetNotFoundException(missing);
        }
        requireAppliesCleanly(request, current);

        CorrectionRequest approved = request.approve(requester.employeeId(),
                LocalDateTime.now(clock));
        requests.update(approved, expectedVersion);

        for (CorrectionItem.Revoke revoke : request.revocations()) {
            timeClocks.revoke(request.employeeId(), request.workDate(),
                    revoke.targetId(), requester.employeeId(), request.reason());
        }
        for (CorrectionItem.Add add : request.additions()) {
            timeClocks.appendCorrection(request.employeeId(), request.workDate(),
                    add.event(), requester.employeeId(), request.reason());
        }

        timeClockService.recalculate(request.employeeId(), request.workDate());
        YearMonth month = YearMonth.from(request.workDate());
        settlements.settle(request.employeeId(), month);
        monthlyAttendances.revertByCorrection(request.employeeId(), month,
                requester.employeeId(), id.value());

        return new CorrectionResult(approved,
                monthlyAttendances.stateOf(request.employeeId(), month));
    }

    /** 訂正を却下する（承認者）。<strong>理由が必須。</strong> */
    @Transactional
    public CorrectionRequest reject(Requester requester, CorrectionRequestId id,
                                    String comment, long expectedVersion) {
        CorrectionRequest request = load(id);
        requireApprover(requester, request);
        CorrectionRequest rejected = request.reject(requester.employeeId(),
                LocalDateTime.now(clock), comment);
        requests.update(rejected, expectedVersion);
        return rejected;
    }

    /**
     * 訂正を取り下げる（本人）。
     *
     * <p>取下げが無いと、誤って申請した本人は
     * <strong>承認者が却下するまで正しい申請を出し直せない。</strong>
     */
    @Transactional
    public CorrectionRequest cancel(Requester requester, CorrectionRequestId id,
                                   long expectedVersion) {
        CorrectionRequest request = load(id);
        CorrectionRequest canceled = request.cancel(requester.employeeId(),
                LocalDateTime.now(clock));
        requests.update(canceled, expectedVersion);
        return canceled;
    }

    /** 1 件の申請。<strong>見てよい社員のぶんだけ。</strong> */
    @Transactional(readOnly = true)
    public CorrectionRequest find(Requester requester, CorrectionRequestId id) {
        CorrectionRequest request = load(id);
        if (!visibility.canView(requester, request.employeeId(), request.workDate())) {
            throw new AccessDeniedException();
        }
        return request;
    }

    /** 承認待ちの一覧。<strong>見てよい社員のぶんだけ返す。</strong> */
    @Transactional(readOnly = true)
    public List<CorrectionRequest> findPendingApproval(Requester requester) {
        return requests.findPending().stream()
                .filter(request -> visibility.canView(requester, request.employeeId(),
                        request.workDate()))
                .toList();
    }

    /**
     * その社員の申請の一覧。<strong>決着したものも含む。</strong>
     *
     * <p>本人が「いつ何を申請して、どうなったか」を辿れるようにする。
     * 承認待ちだけを返すと、却下された申請が画面から消えて理由が読めなくなる。
     */
    @Transactional(readOnly = true)
    public List<CorrectionRequest> findByEmployee(Requester requester,
                                                  EmployeeId employeeId) {
        return requests.findByEmployee(employeeId).stream()
                .filter(request -> visibility.canView(requester, employeeId,
                        request.workDate()))
                .toList();
    }

    /** 現在の版（API設計書 1.1）。取得する経路が無いと利用者は版を送れない。 */
    @Transactional(readOnly = true)
    public long currentVersion(Requester requester, CorrectionRequestId id) {
        find(requester, id);
        return requests.currentVersion(id);
    }

    private CorrectionRequest load(CorrectionRequestId id) {
        return requests.find(id).orElseThrow(() -> new CorrectionNotFoundException(id));
    }

    /**
     * 訂正申請の承認者は、<strong>{@code workDate} が属する月の承認者</strong>とする。
     *
     * <p>月次と訂正で承認者が食い違うと、
     * 「月次は承認できるが訂正は承認できない」上長が生まれる。
     * 日をまたぐ勤務では {@code workDate} が始業日なので、
     * 3/31 22:00 出勤 → 4/1 06:00 退勤の訂正は 3 月の承認者が扱う（BR-03）。
     */
    private void requireApprover(Requester requester, CorrectionRequest request) {
        Approver approver = approverPolicy.resolve(request.employeeId(),
                YearMonth.from(request.workDate()), LocalDate.now(clock));
        if (!approver.isApprovedBy(requester.employeeId(), requester.has(Role.HR))) {
            throw new AccessDeniedException();
        }
    }

    /**
     * その月が訂正申請を受け付ける状態か。
     *
     * <p><strong>締め済みと承認済みを分ける。</strong>
     * 承認済みは締め済みではない。承認を取り消せば直せるので、
     * 利用者への案内がまったく違う。粗い型にまとめない。
     */
    private void requireMonthAcceptsCorrection(EmployeeId employeeId, LocalDate workDate) {
        YearMonth month = YearMonth.from(workDate);
        if (monthClosure.isClosed(employeeId, month)) {
            throw new MonthAlreadyClosedException(month);
        }
        if (!monthClosure.acceptsCorrectionRequest(employeeId, month)) {
            throw new MonthNotEditableException(month);
        }
    }

    /** 訂正を適用したあとの打刻列が、状態機械として妥当か。 */
    private static void requireAppliesCleanly(CorrectionRequest request,
                                              List<RecordedTimeClockEvent> current) {
        try {
            request.applyTo(current).validateTransitions();
        } catch (TimeClockSequenceException e) {
            throw new InvalidCorrectionSequenceException(e.getMessage());
        }
    }

    /** 訂正の承認の結果。<strong>月次勤怠が下書きに戻ったことを呼び出し側へ返す。</strong> */
    public record CorrectionResult(CorrectionRequest request,
                                   jp.co.sample.kintai.approval.domain.AttendanceState
                                           monthlyAttendanceState) {
    }

    /** 申請が見つからない。 */
    public static final class CorrectionNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        CorrectionNotFoundException(CorrectionRequestId id) {
            super("訂正申請が見つかりません: " + id.value());
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
            return "訂正申請が見つかりません";
        }
    }

    /** 同一勤務日に未処理の申請がある。 */
    public static final class PendingCorrectionExistsException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        PendingCorrectionExistsException(LocalDate workDate) {
            super("その勤務日には未処理の訂正申請があります: " + workDate);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:pending-correction-exists";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "その勤務日には未処理の訂正申請があります";
        }
    }

    /** 締め済みの月。<strong>戻す手段が無い。</strong> */
    public static final class MonthAlreadyClosedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month) {
            super("締め済みの月は訂正できません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-already-closed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "締め済みの月は訂正できません";
        }
    }

    /** 承認済みの月。<strong>締め済みとは違う。</strong> 承認を取り消せば直せる。 */
    public static final class MonthNotEditableException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthNotEditableException(YearMonth month) {
            super("承認済みの月は訂正できません。承認の取消が要ります: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-not-editable";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "承認済みの月は訂正できません";
        }
    }

    /** 取り消す対象の打刻が実在しない。 */
    public static final class CorrectionTargetNotFoundException extends DomainException
            implements DetailedDomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient List<TimeClockEventId> missing;

        CorrectionTargetNotFoundException(List<TimeClockEventId> missing) {
            super("取り消す打刻が見つかりません: " + missing.size() + " 件");
            this.missing = List.copyOf(missing);
        }

        /** <strong>どの打刻が見つからないかを返す。</strong> 画面が指し直せるようにする。 */
        @Override
        public Map<String, Object> properties() {
            return Map.of("missingTargetIds", missing.stream()
                    .map(id -> id.value().toString()).toList());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:correction-target-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "取り消す打刻が見つかりません";
        }
    }

    /**
     * 訂正を適用すると打刻列が壊れる。
     *
     * <p><strong>業務上ありえない入力なので 422。</strong>
     * 状態が変われば通るわけではないので 409 にはしない。
     */
    public static final class InvalidCorrectionSequenceException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        InvalidCorrectionSequenceException(String detail) {
            super(detail);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-time-clock-sequence";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "この訂正では打刻の順序が不正になります";
        }
    }
}
