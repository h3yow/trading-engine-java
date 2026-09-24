package com.github.h3yow.tradingenginejava.matching.model;

/** 委托类型：限价单 / 市价单（市价单按 IOC 语义处理，未成交余量立即撤销）。 */
public enum OrderType {
    LIMIT,
    MARKET
}
