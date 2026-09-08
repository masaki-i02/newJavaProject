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
  readonly roles: readonly Role[];
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
 * ★ 一覧の行では判断の項目が省かれる（`undefined`）。
 *   行ごとに承認者と履歴を引くと、社員数ぶんの問い合わせが重複するためである。
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
