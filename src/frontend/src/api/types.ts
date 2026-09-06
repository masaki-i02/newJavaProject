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

export interface DailyAttendance {
  readonly workDate: WallClockDate;
  readonly workingMinutes: number;
  readonly breakMinutes: number;
  readonly overtimeMinutes: number;
  readonly nightMinutes: number;
  readonly legalHolidayMinutes: number;
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
  readonly employeeId?: string;
}

export interface Warning {
  readonly type: string;
  readonly dates?: readonly WallClockDate[];
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
