package jp.co.sample.kintai.approval.presentation;

import java.util.List;

import jp.co.sample.kintai.approval.domain.Approver;
import jp.co.sample.kintai.approval.domain.ApproverKind;
import jp.co.sample.kintai.approval.domain.ResolutionStep;

/**
 * 承認者と、そこへ至った経路（BR-11）。
 *
 * <p><strong>経路を返す。</strong>
 * 「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
 * 結論だけを返すと、そのたびに人が組織の履歴を辿ることになる。
 *
 * <p><strong>氏名を返さない。</strong>
 * 氏名は {@code employee} が所有する概念であり、
 * ここへ混ぜると持っていない情報の提供者になってしまう（設計規約チェックリスト 3）。
 * 画面は社員 ID と部署 ID で引き直す。
 */
record ApproverResponse(ApproverKind kind, String employeeId, List<StepResponse> path) {

    static ApproverResponse from(Approver approver) {
        return new ApproverResponse(approver.kind(),
                approver.employeeId().map(id -> id.value().toString()).orElse(null),
                approver.path().stream().map(StepResponse::from).toList());
    }

    /** 経路の 1 段。その部署でなぜ決まらなかったか。 */
    record StepResponse(String departmentId, String departmentName, String reason) {

        static StepResponse from(ResolutionStep step) {
            return new StepResponse(step.department().id().value().toString(),
                    step.department().name(), step.reason().name());
        }
    }
}
