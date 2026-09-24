package com.github.h3yow.tradingenginejava.matching.model;

import java.math.BigDecimal;

/** 一笔成交（吃单委托与挂单委托之间按某个价格撮合的量）。 */
public record Trade(
        long seq,
        String symbol,
        long tradeId,
        String takerClientOrderId,
        String makerClientOrderId,
        String buyerAccountId,
        String sellerAccountId,
        Side takerSide,
        BigDecimal price,
        long quantity
) {}
