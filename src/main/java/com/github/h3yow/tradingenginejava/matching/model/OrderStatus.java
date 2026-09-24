package com.github.h3yow.tradingenginejava.matching.model;

/**
 * 委托生命周期状态：
 * NEW 未成交；PARTIALLY_FILLED 部分成交；FILLED 全部成交；
 * CANCELED 已撤销（含改单撤旧、市价单余量撤销、拒绝后不进入订单簿）；
 * REJECTED 委托被拒绝（从未进入订单簿）。
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED
}
