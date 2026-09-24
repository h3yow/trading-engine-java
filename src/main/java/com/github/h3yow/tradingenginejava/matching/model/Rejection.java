package com.github.h3yow.tradingenginejava.matching.model;

/** 拒绝记录：指令序号 + 客户端委托号 + 可区分的拒绝原因 + 解释文本。 */
public record Rejection(
        long seq,
        String clientOrderId,
        RejectReason reason,
        String detail
) {}
