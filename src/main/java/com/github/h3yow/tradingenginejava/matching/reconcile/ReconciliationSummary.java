package com.github.h3yow.tradingenginejava.matching.reconcile;

import java.util.Map;

/**
 * 盘后对账摘要（不可变、确定性）。
 *
 * <p>按账户、按标的聚合。同一状态重复生成的摘要逐字段一致（账户、标的、维度均有序输出）。
 *
 * @param accounts 账户维度（订单数、成交笔数、成交量、现金、各标的持仓），按账户 ID 升序
 * @param symbols  标的维度（成交笔数、成交量），按标的升序
 */
public record ReconciliationSummary(
        Map<String, AccountSummary> accounts,
        Map<String, SymbolSummary> symbols
) {
    /** 单账户维度。positions 为该账户各标的净持仓，按标的升序。 */
    public record AccountSummary(
            long orderCount,
            long tradeCount,
            long filledQuantity,
            String cash,
            Map<String, Long> positions
    ) {
    }

    /** 单标的维度（双边）。 */
    public record SymbolSummary(
            long tradeCount,
            long filledQuantity
    ) {
    }
}
