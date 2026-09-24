package com.github.h3yow.tradingenginejava.engine;

import com.github.h3yow.tradingenginejava.model.Order;
import com.github.h3yow.tradingenginejava.model.Trade;

import java.util.List;
import java.util.Map;

/**
 * 引擎快照：足以完整重建撮合状态。
 * activeOrders 为仍在簿中的订单（按 orderId 索引）；
 * allOrders 含已成交 / 已撤销 / 被拒的全部订单（按 orderId 索引）；
 * accounts 为账户资产快照；nextTradeSeq 保证恢复后成交序号不冲突。
 */
public record EngineSnapshot(
        long nextSeq,
        long nextTradeSeq,
        Map<String, Order> allOrders,
        List<Order> activeOrders,
        List<Trade> trades,
        List<AccountSnapshot> accounts
) {
    public record AccountSnapshot(
            String accountId,
            long cash,
            long reservedCash,
            Map<String, Long> positions,
            Map<String, Long> reservedPositions
    ) {
    }
}
