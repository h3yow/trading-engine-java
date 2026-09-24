package com.github.h3yow.tradingenginejava.model;

/** 拒绝原因，必须可按原因区分。 */
public enum RejectReason {
    INVALID_QUANTITY,
    INVALID_PRICE,
    UNKNOWN_ACCOUNT,
    UNKNOWN_ORDER,
    ORDER_NOT_ACTIVE,
    DUPLICATE_ORDER_ID,
    SELF_TRADE,
    INSUFFICIENT_CASH,
    INSUFFICIENT_POSITION,
    NO_LIQUIDITY,
    INVALID_SEQUENCE
}
