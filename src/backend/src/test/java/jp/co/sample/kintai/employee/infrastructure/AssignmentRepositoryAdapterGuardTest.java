package jp.co.sample.kintai.employee.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * アダプタが持つ<strong>二重の守り</strong>を直接叩く。
 *
 * <p>「開いた所属が複数あれば例外」という検査は、DB の
 * {@code assignments_no_overlap} が生きているかぎり<strong>到達できない</strong>。
 * 結合テストからは踏めないので、そこだけを取り出して確かめる。
 *
 * <p>制約が落ちた状態で黙って 1 つだけ閉じると、残りが無期限のまま残り、
 * その社員は 2 つの部署に永久に所属し続ける。
 */
@DisplayName("所属アダプタの防波堤")
class AssignmentRepositoryAdapterGuardTest {

    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final LocalDate JULY_1 = LocalDate.of(2026, 7, 1);

    private static AssignmentEntity open(LocalDate from) {
        var entity = new AssignmentEntity(UUID.randomUUID());
        entity.setEmployeeId(TARO.value());
        entity.setDepartmentId(UUID.randomUUID());
        entity.setValidFrom(from);
        return entity;
    }

    @Test
    @DisplayName("開いた所属が 2 件あれば、黙って閉じずに例外にする")
    void twoOpenAssignmentsAreRejected() {
        var jpa = mock(AssignmentJpaRepository.class);
        when(jpa.findOpen(TARO.value()))
                .thenReturn(List.of(open(LocalDate.of(2026, 4, 1)),
                        open(LocalDate.of(2020, 1, 1))));

        assertThatThrownBy(() -> new AssignmentRepositoryAdapter(jpa).close(TARO, JULY_1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("開いている所属が 2 件あります");
    }

    @Test
    @DisplayName("開いた所属が 1 件なら閉じる")
    void oneOpenAssignmentIsClosed() {
        var jpa = mock(AssignmentJpaRepository.class);
        var entity = open(LocalDate.of(2026, 4, 1));
        when(jpa.findOpen(TARO.value())).thenReturn(List.of(entity));

        assertThatCode(() -> new AssignmentRepositoryAdapter(jpa).close(TARO, JULY_1))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(entity.getValidTo()).isEqualTo(JULY_1);
    }

    /** 退職済みなどで開いた所属が無い場合は、何もしないのが正しい。 */
    @Test
    @DisplayName("開いた所属が無ければ何もしない")
    void noOpenAssignment() {
        var jpa = mock(AssignmentJpaRepository.class);
        when(jpa.findOpen(TARO.value())).thenReturn(List.of());

        assertThatCode(() -> new AssignmentRepositoryAdapter(jpa).close(TARO, JULY_1))
                .doesNotThrowAnyException();
    }
}
