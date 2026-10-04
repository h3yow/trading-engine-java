package com.github.h3yow.tradingenginejava.matching.reconcile;

/**
 * 对账差异项（不可变）。
 *
 * @param scope     差异范围：ACCOUNT（账户维度）/ SYMBOL（标的维度）
 * @param id        对不上的账户 ID 或标的代码
 * @param dimension 对不上的维度名（orderCount/tradeCount/filledQuantity/cash/position）
 * @param expected  期望侧（基准实例）的取值文本
 * @param actual    实际侧（被核对实例）的取值文本
 */
public record Mismatch(
        Scope scope,
        String id,
        String dimension,
        String expected,
        String actual
) {
    public enum Scope {
        ACCOUNT,
        SYMBOL
    }
}
