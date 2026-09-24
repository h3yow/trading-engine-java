package com.github.h3yow.tradingenginejava.model;

/**
 * 订单状态机：
 * NEW -> PARTIALLY_FILLED -> FILLED
 * NEW/PARTIALLY_FILLED -> CANCELLED（含市价单无流动性后的剩余撤销）
 * 未通过校验的委托不会生成订单，以 REJECTED 结果返回原因。
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED
}
