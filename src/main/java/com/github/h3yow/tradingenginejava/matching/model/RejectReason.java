package com.github.h3yow.tradingenginejava.matching.model;

/** 委托拒绝原因，可按原因区分处理。 */
public enum RejectReason {
    /** 数量非法（<=0 或改单后数量不大于已成交数量）。 */
    INVALID_QUANTITY,
    /** 价格非法（限价单价格<=0）。 */
    INVALID_PRICE,
    /** 未知委托（撤单/改单引用的 clientOrderId 不存在或已终结）。 */
    UNKNOWN_ORDER,
    /** 会造成自成交（同账户对手挂单）。 */
    SELF_TRADE,
    /** 买入资金不足（可用现金无法覆盖冻结需求）。 */
    INSUFFICIENT_CASH,
    /** 卖出持仓不足（可用持仓无法覆盖卖出冻结）。 */
    INSUFFICIENT_POSITION,
    /** 超过风控限额（最大在途委托数 / 单笔最大名义金额）。 */
    LIMIT_EXCEEDED,
    /** 市价单进入订单簿时没有任何可成交对手盘。 */
    MARKET_NO_LIQUIDITY,
    /** 幂等键冲突：同一 clientOrderId 或同一指令序号重复提交。 */
    DUPLICATE_COMMAND,
    /** 指令本身不合法（未知类型、缺少字段等）。 */
    INVALID_COMMAND,
    /** 委托组原子提交失败：批次级校验未过或组内某条被拒，整组回滚、零生效。 */
    BATCH_REJECTED
}
