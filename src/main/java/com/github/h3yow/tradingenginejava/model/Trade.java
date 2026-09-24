package com.github.h3yow.tradingenginejava.model;

/**
 * 一笔成交（taker 委托与 maker 挂单撮合产生）。
 * 价格取 maker 价（被动挂单价）。
 */
public record Trade(
        long tradeSeq,
        long commandSeq,
        String symbol,
        long price,
        long qty,
        String takerOrderId,
        String takerAccountId,
        Side takerSide,
        String makerOrderId,
        String makerAccountId
) {
}
