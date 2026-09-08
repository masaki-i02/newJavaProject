import type { WallClockDate, WallClockDateTime, YearMonth } from './wallClock';

/**
 * サーバの応答の型。
 *
 * ★ 手書きにする（未決事項 #3 を閉じる）。
 *   OpenAPI から生成しない理由は 3 つ。
 *   1. 使うのは全体の 1〜2 割で、残りは死んだ型になる
 *   2. **生成しても、守りたい制約が型に出ない。** springdoc が出すのは
 *      `status: string` であって `'DRAFT' | 'SUBMITTED' | ...` ではない
 *   3. springdoc を依存に入れると、AR-01 の禁止先に実在するクラスが増える。
 *      入れるなら違反クラスと一緒に戻す必要がある（CLAUDE.md 落とし穴 87）
 *
 * ★ 手書きの弱点は「バックエンドが変わっても落ちない」ことである。
 *   だから **判別値は必ずユニオンで書く。** サーバの `enum` が増えたときに、
 *   網羅性検査つきの `switch` がコンパイルエラーになる。
 */

/** 打刻の種別（BR-02）。サーバの `TimeClockEvent.Type` と対応する。 */
export type PunchType = 'CLOCK_IN' | 'BREAK_START' | 'BREAK_END' | 'CLOCK_OUT';

/** 打刻の状態機械の状態。 */
export type PunchStatus = 'NOT_STARTED' | 'WORKING' | 'ON_BREAK' | 'FINISHED';

/** 月次勤怠の状態（BR-10）。 */
export type AttendanceState = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'CLOSED';

/** 承認者の種別（BR-11）。 */
export type ApproverKind = 'INDIVIDUAL' | 'HUMAN_RESOURCES' | 'NONE';

/** ロール。`APPROVER` は認証時に部署長の事実から導出される。 */
export type Role = 'EMPLOYEE' | 'APPROVER' | 'HR' | 'ADMIN';

/**
 * ログイン中の社員（01 API 設計書 3.9）。
 *
 * ★ 項目名をサーバに合わせる。応答は `id` であって `employeeId` ではない。
 *   手書きの型は「サーバがそう返すはず」という思い込みをそのまま固定するので、
 *   コンパイルは通るのに `/api/employees/undefined/...` を呼ぶ画面ができる。
 */
export interface SignedIn {
  readonly id: string;
  readonly employeeNumber: string;
  readonly name: string;
  readonly email: string;
  readonly hiredOn: WallClockDate;
  readonly roles: readonly Role[];
  /**
   * 所属。
   *
   * ★ **省略ではなく `null` が来る。** 一般社員は社員の一覧を見られないので、
   *   自分の所属を知る経路がここにしか無い。
   *   未来日入社の社員は、基準日の時点でまだどこにも所属していない。
   */
  readonly department: EmployeeDepartment | null;
}

export interface Punch {
  readonly id: string;
  readonly type: PunchType;
  readonly occurredAt: WallClockDateTime;
}

/**
 * 現在の勤務状態（SC-02）。
 *
 * ★ `availableActions` だけでボタンを出し分ける。
 *   `status` から組み立て直すと、状態機械が画面にも生まれる（原則 1）。
 */
export interface CurrentAttendance {
  readonly workDate: WallClockDate;
  readonly status: PunchStatus | null;
  readonly availableActions: readonly PunchType[];
  readonly punches: readonly Punch[];
  readonly unclosedWorkDates: readonly WallClockDate[];
}

/**
 * 期間の日次勤怠（03 API 設計書 3.1）。
 *
 * ★ 要求した期間がそのまま返る。空だったときに
 *   「勤怠が無い」のか「期間を取り違えた」のかを区別できるようにするため。
 */
export interface AttendanceList {
  readonly from: WallClockDate;
  readonly toExclusive: WallClockDate;
  readonly days: readonly DailyAttendance[];
}

/**
 * 日次の勤怠（03 API 設計書 3.1）。
 *
 * ★ 時間外は 2 列である。1 つに束ねない。
 *   法定内残業（所定超・法定内。割増 0%）と法定外残業（法定超。割増 25%）は
 *   支払う賃金が違う（CLAUDE.md 落とし穴 115）。
 *   `overtimeMinutes` という項目はサーバに存在しない。
 *
 * ★ 月次の `MonthlySettlement.overtimeMinutes` とは別物である。
 *   月次は 1 列で、フレックスでは日次からは導けない。
 */
export interface DailyAttendance {
  readonly workDate: WallClockDate;
  readonly dayType: string;
  readonly workingMinutes: number;
  readonly breakMinutes: number;
  readonly baseMinutes: number;
  readonly overtimeWithinStatutoryMinutes: number;
  readonly overtimeBeyondStatutoryMinutes: number;
  readonly nightMinutes: number;
  readonly legalHolidayMinutes: number;
  readonly breakRequirementSatisfied: boolean;
}

export interface MonthlySettlement {
  readonly month: YearMonth;
  readonly period: { readonly from: WallClockDate; readonly toExclusive: WallClockDate };
  readonly workingTimeSystem: 'FIXED' | 'FLEX';
  readonly scheduledTotalMinutes: number;
  readonly workingMinutes: number;
  readonly overtimeMinutes: number;
  readonly shortageMinutes: number;
  /**
   * コアタイム不在（BR-05）。
   *
   * ★ **賃金の計算には影響しない。** 承認者への警告として示すだけである。
   *   固定時間制にはコアタイムという概念が無いので常に 0。
   */
  readonly coreTimeAbsenceMinutes: number;
}

export interface HistoryEntry {
  readonly eventKind: string;
  readonly fromStatus: AttendanceState;
  readonly toStatus: AttendanceState;
  readonly actorId: string;
  readonly comment?: string;
  readonly occurredAt: WallClockDateTime;
}

export interface Approver {
  readonly kind: ApproverKind;
  // ★ 省略ではなく null が来る。ApproverResponse に @JsonInclude が無いため。
  //   `?:` と書くと `null` は型の上で存在しないことになり、
  //   `!== undefined` で判定した瞬間に承認者欄へ null と表示される
  readonly employeeId: string | null;
}

/**
 * 手続きを止めない知らせ（05 API 設計書 2.2）。
 *
 * ★ `dates` は必ず 1 件以上ある。サーバは日付が無ければ警告そのものを載せない。
 *   省略可能と書くと、画面に恒真の分岐が残り続ける。
 */
export interface Warning {
  readonly type: string;
  readonly dates: readonly WallClockDate[];
}

/**
 * 月次勤怠（SC-03 / SC-06）。
 *
 * ★ `canSubmit` / `canApprove` はサーバが返す。
 *   状態から画面で導くと、提出の条件（対象月が終わったか、未確定の日が無いか）
 *   まで画面に複製することになる。
 *
 * ★ **一覧の行はこの型では受けない**（`PendingApproval` を使う）。
 *   同じ型を使い回すと、一覧に無い項目を読んでも TypeScript が止めてくれず、
 *   `undefined` が画面に出るだけになる（落とし穴 143・151）。
 */
export interface MonthlyAttendance {
  readonly employeeId: string;
  readonly month: YearMonth;
  readonly status: AttendanceState;
  readonly version: number;
  readonly submittedAt?: WallClockDateTime;
  readonly submittedBy?: string;
  readonly approvedAt?: WallClockDateTime;
  readonly approvedBy?: string;
  readonly closedAt?: WallClockDateTime;
  readonly closedBy?: string;
  readonly acceptsTimeClock?: boolean;
  readonly acceptsCorrectionRequest?: boolean;
  readonly canSubmit?: boolean;
  readonly canApprove?: boolean;
  readonly approver?: Approver;
  readonly history?: readonly HistoryEntry[];
  readonly warnings?: readonly Warning[];
}

// ---------------------------------------------------------------------------
// 就業規則と会社カレンダー（SC-10 / SC-11）
// ---------------------------------------------------------------------------

/** 労働時間制度。DB の `work_rules` が排他の CHECK 制約で守っている区別。 */
export type WorkingTimeSystemType = 'FIXED' | 'FLEX';

/** 深夜帯。法が認めるのは 2 つだけなので、時刻ではなく名前で分岐する。 */
export type NightWindowName = 'STANDARD' | 'DESIGNATED_AREA';

/** 暦日区分。 */
export type DayType = 'WORKDAY' | 'LEGAL_HOLIDAY' | 'NON_LEGAL_HOLIDAY';

/** 曜日。サーバは `java.time.DayOfWeek` の名前で受ける。 */
export type DayOfWeekName =
  | 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY'
  | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export interface NightWindowView {
  readonly name: NightWindowName;
  /** `22:00:00` の形。 */
  readonly start: string;
  readonly end: string;
}

/**
 * 割増率。
 *
 * ★ 文字列で受ける。JSON の数値にすると浮動小数点になり、
 *   `0.250` と `0.25` が別の値になる（CLAUDE.md 落とし穴 34 が画面に現れた形）。
 *   画面は表示するだけなので、数値へ直さない。
 */
export interface PremiumRatesView {
  readonly overtimeBeyondStatutory: string;
  readonly night: string;
  readonly legalHoliday: string;
}

export interface FixedTimeView {
  readonly scheduledStart: string;
  readonly scheduledEnd: string;
  readonly scheduledBreakMinutes: number;
  readonly scheduledWorkingMinutes: number;
}

export interface FlextimeView {
  readonly flexibleStart: string;
  readonly flexibleEnd: string;
  readonly coreStart: string;
  readonly coreEnd: string;
  readonly standardDailyMinutes: number;
}

/**
 * 就業規則の版（02 API 設計書 2.1）。
 *
 * ★ `fixedTime` と `flextime` は排他である。使わないほうのキーは応答に出ない。
 *   両方を必須で書くと、画面が「FLEX なのに始業時刻がある」形を作れてしまう。
 *
 * ★ 期間の上限は `validToExclusive`。現行版では省かれる（＝上限が無い）。
 *   閉区間の最終日として見せるときは `previousDayOf` を通す（落とし穴 10・112）。
 */
interface WorkRuleRevisionBase {
  readonly workRuleId: string;
  readonly validFrom: WallClockDate;
  readonly validToExclusive?: WallClockDate;
  readonly statutoryDailyMinutes: number;
  readonly statutoryWeeklyMinutes: number;
  readonly nightWindow: NightWindowView;
  readonly premiumRates: PremiumRatesView;
}

export type WorkRuleRevision = WorkRuleRevisionBase & (
  | { readonly workingTimeSystem: 'FIXED'; readonly fixedTime: FixedTimeView }
  | { readonly workingTimeSystem: 'FLEX'; readonly flextime: FlextimeView }
);

/**
 * 就業規則の系列。
 *
 * ★ 参照するのは系列であって版ではない（ADR 0003 / 落とし穴 13）。
 *   一覧では `revisions` が返らない（全系列の全版を返すと重い）ので、
 *   省略可能にしてある。
 *
 * ★ `version` は系列の楽観ロックの版で、**0 から始まる**（02 API 設計書 2.0）。
 *   月次勤怠の版が 1 から始まるのとは別の理由に基づく。
 */
export interface WorkRuleSeries {
  readonly seriesId: string;
  readonly name: string;
  readonly abolishedOn?: WallClockDate;
  readonly version: number;
  /**
   * ★ 省略ではなく `null` が来る。`WorkRuleResponse.revisions` に
   *   `@JsonInclude` が付いていないためである。`?:` と書くと
   *   型の上では `null` が存在しないことになり、`?? []` を書かないまま
   *   `revisions.map` を呼ぶ画面がコンパイルを通ってしまう（`Approver` と同型）。
   */
  readonly revisions: readonly WorkRuleRevision[] | null;
}

/**
 * 所定総労働時間が法定の総枠を超える月の知らせ。
 *
 * ★ 拒否ではない。フレックスでは適法に起こりうる。
 *   握りつぶすと人事が気づけないので、成功メッセージと並べて必ず出す。
 */
export interface ScheduleWarning {
  readonly code: string;
  readonly message: string;
  readonly month: YearMonth;
  readonly scheduledTotalMinutes: number;
  readonly statutoryTotalLimitMinutes: number;
}

/** 登録・改定の応答。読み直したあとの版が入っている（落とし穴 158）。 */
export interface WorkRuleRegistration {
  readonly seriesId: string;
  readonly workRuleId: string;
  readonly version: number;
  readonly warnings?: readonly ScheduleWarning[];
}

/** 会社カレンダーの 1 日。未登録の日も `WORKDAY` として返る。 */
export interface CalendarDay {
  readonly date: WallClockDate;
  readonly dayType: DayType;
}

export interface CalendarView {
  readonly from: WallClockDate;
  readonly toExclusive: WallClockDate;
  readonly days: readonly CalendarDay[];
  readonly workdayCount: number;
}

/** 一括登録の知らせ（「連続 7 日に法定休日が無い」）。 */
export interface CalendarWarning {
  readonly code: string;
  readonly message: string;
  readonly period: { readonly from: WallClockDate; readonly toExclusive: WallClockDate };
}

export interface CalendarBulkResult {
  readonly registeredCount: number;
  readonly byDayType: Readonly<Partial<Record<DayType, number>>>;
  readonly warnings?: readonly CalendarWarning[];
}

/**
 * 就業規則が引けない在籍者（02 API 設計書 2.4）。
 *
 * ★ 社員番号も氏名も返らない。`employee` が所有する概念なので混ぜない。
 *   この一覧が空でないと、その社員の勤怠は計算できない。
 */
export interface UnassignedWorkRules {
  readonly date: WallClockDate;
  readonly employeeIds: readonly string[];
}

/**
 * 締める前の 1 行（SC-08）。
 *
 * ★ **版を持たない。** 締めは版を取らない `bulk-closure` で行う。
 *   一覧に版を載せると、行が無い月の版 0 が画面へ渡り、
 *   落とし穴 57 が防いだ経路が一覧側から開く。
 *
 * ★ `DRAFT` を「打刻が 1 件も無い」と読まない。
 *   行は提出時に初めて作られるので、行が無いことは「下書き」を意味するだけである
 *   （落とし穴 120）。
 *
 * ★ `canClose` はサーバが決める。`status === 'APPROVED'` と書くと、
 *   人事であることと対象月が終わっていることが画面から落ちる。
 */
export interface ClosureStatus {
  readonly employeeId: string;
  readonly month: YearMonth;
  readonly status: AttendanceState;
  readonly canClose: boolean;
  /** 締められないときだけ入る。 */
  readonly reason?: string;
}

/** 一括締めで締められなかった社員。 */
export interface SkippedClosure {
  readonly employeeId: string;
  readonly status: AttendanceState;
  readonly reason: string;
}

export interface BulkClosureResult {
  readonly month: YearMonth;
  readonly closed: number;
  readonly skipped: readonly SkippedClosure[];
}

// ---------------------------------------------------------------------------
// 社員・組織と 36 協定（SC-09 / SC-12 / SC-13 / SC-14）
// ---------------------------------------------------------------------------

/**
 * 所属。
 *
 * ★ **省略ではなく `null` が来る。** `EmployeeResponse` は `version` にだけ
 *   `@JsonInclude` を付けており、`department` と `retiredOn` の `null` は
 *   そのまま出る（落とし穴 76）。`?:` と書くと「所属が無い」ことを
 *   応答から読み取れなくなる。
 */
export interface EmployeeDepartment {
  readonly id: string;
  readonly code: string;
  readonly name: string;
}

/**
 * 社員（01 API 設計書 3.1）。
 *
 * ★ `version` は詳細だけに入る。一覧で行ごとに引くと、
 *   社員数ぶんの問い合わせと認可判定が重複する。
 *   更新するのは開いている 1 人だけなので、詳細を開いてから版を得る。
 *
 * ★ `department` が `null` なのは異常ではない。
 *   **未来日入社の社員**は、基準日の時点でまだどこにも所属していない。
 */
export interface EmployeeRow {
  readonly id: string;
  readonly employeeNumber: string;
  readonly name: string;
  readonly email: string;
  readonly hiredOn: WallClockDate;
  readonly retiredOn: WallClockDate | null;
  readonly roles: readonly Role[];
  readonly department: EmployeeDepartment | null;
  readonly version?: number;
}

export interface EmployeeList {
  readonly employees: readonly EmployeeRow[];
}

/** 部署長。就任日を添える。 */
export interface DepartmentManager {
  readonly id: string;
  readonly name: string;
  readonly since: WallClockDate;
}

/**
 * 部署ツリーの節（01 API 設計書 3.x）。
 *
 * ★ **閲覧範囲は API が絞る。** 画面は返ってきた木をそのまま描く。
 *   画面側で絞ると、API を直接叩けば全社が見えてしまう。
 */
export interface DepartmentNode {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly abolishedOn: WallClockDate | null;
  readonly manager: DepartmentManager | null;
  readonly children: readonly DepartmentNode[];
}

export interface DepartmentTree {
  readonly departments: readonly DepartmentNode[];
}

/**
 * 36 協定の超過（BR-12）。
 *
 * ★ `subjectMinutes` に**法定休日労働は入っていない。**
 *   限度時間（月 45 時間・年 360 時間）の対象は時間外労働だけである
 *   （36 条 3 項・4 項。落とし穴 52）。休日労働を含めるのは 6 項 2 号・3 号という
 *   別の規制なので、画面でも足さない。
 */
export interface AgreementAlert {
  readonly employeeId: string;
  readonly subjectMinutes: number;
  readonly monthlyLimitMinutes: number;
  readonly exceedsMonthly: boolean;
  readonly annualUsedBeforeMinutes: number;
  readonly annualLimitMinutes: number;
  readonly exceedsAnnual: boolean;
}

export interface AgreementAlerts {
  readonly month: YearMonth;
  readonly alerts: readonly AgreementAlert[];
  readonly summary: {
    readonly monthlyExceeded: number;
    readonly annualExceeded: number;
  };
}

// ---------------------------------------------------------------------------
// 打刻の訂正申請（SC-04 / SC-07）
// ---------------------------------------------------------------------------

/**
 * 訂正申請の状態。
 *
 * ★ 申請中は **`SUBMITTED`** である（`REQUESTED` ではない）。
 *   DB の `correction_requests_status_check` がこの 4 つに限っている。
 *   手書きの型はサーバを検査しないので、綴りを取り違えても
 *   型検査もフロントの単体テストも通り、**状態の列が空欄になるだけ**だった
 *   （落とし穴 143・151）。実物のバックエンドに当てて初めて出た。
 *
 * ★ 月次勤怠の `SUBMITTED` とは**別の状態機械**である。名前が同じだけで、
 *   同じ型として扱わない。
 */
export type CorrectionStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELED';

/**
 * 訂正の 1 項目。
 *
 * ★ **「変更」という操作は無い。** 取消（`REVOKE`）と追加（`ADD`）の
 *   組み合わせで表す。変更を許すと元の打刻の値が失われ、
 *   「何がどう直ったのか」を利用者が確かめられなくなる（BR-09 の目的）。
 *
 * ★ 判別可能ユニオンで受ける。平坦に並べると
 *   「REVOKE なのに occurredAt がある」形を画面が作れてしまい、
 *   サーバの検証（`correction_items_variant_check` と同じ不変条件）に弾かれる。
 */
export type CorrectionItem =
  | { readonly action: 'REVOKE'; readonly targetEventId: string }
  | {
    readonly action: 'ADD';
    readonly eventType: PunchType;
    readonly occurredAt: WallClockDateTime;
  };

export interface CorrectionRequest {
  readonly id: string;
  readonly employeeId: string;
  readonly workDate: WallClockDate;
  readonly status: CorrectionStatus;
  readonly reason: string;
  readonly version: number;
  readonly items: readonly CorrectionItem[];
}

/** 承認の結果。月次勤怠が下書きへ戻ったことを含む。 */
export interface CorrectionApproval {
  readonly request: CorrectionRequest;
  readonly monthlyAttendanceStatus: AttendanceState;
}

/**
 * 訂正の対象にできる打刻（03 API 設計書 2.3）。
 *
 * ★ **取り消された打刻も返る。** BR-09 の目的は「何がどう直ったか」を
 *   利用者が確かめられることなので、有効な打刻だけを返すとその目的を果たせない。
 *   取消済みを対象にした申請は 409 になるので、画面では選ばせない。
 *
 * ★ `revoked` は**必ず来る**（`boolean`、省略なし）。
 *   `?:` と書くと、画面に恒真の分岐が残り続ける。
 */
export interface RecordedPunch {
  readonly id: string;
  readonly type: PunchType;
  readonly occurredAt: WallClockDateTime;
  readonly source: string;
  readonly reason?: string;
  readonly revoked: boolean;
  readonly revocation?: {
    readonly reason: string;
    readonly recordedBy: string;
    readonly recordedAt: WallClockDateTime;
  };
}

/**
 * 承認待ちの 1 行（05 API 設計書 2.6）。
 *
 * ★ **`version` を持たない。** 決裁するのは詳細を開いた 1 人だけなので、
 *   版はそちらで取る。一覧に載せると、行ごとに閲覧範囲の判定が 2 回走る。
 *
 * ★ **労働時間も持たない。** `attendance` が所有する概念であり、
 *   社員番号・氏名を返さないのと同じ理由である。
 *   承認者は詳細（SC-06）で月次清算と日次を引く。
 */
export interface PendingApproval {
  readonly employeeId: string;
  readonly month: YearMonth;
  readonly status: AttendanceState;
  readonly submittedAt: WallClockDateTime;
  /** 人事による代理提出か。**サーバが導く**（画面で `submittedBy` と比べない）。 */
  readonly proxySubmitted: boolean;
}
