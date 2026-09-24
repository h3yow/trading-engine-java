package com.github.h3yow.tradingenginejava.matching.engine;

import java.math.BigDecimal;

/**
 * 风控限额配置（全局统一）：
 * maxOpenOrdersPerAccount 单账户最大在途委托数；
 * maxOrderNotional 单笔委托最大名义金额（限价=限价*数量，市价按下单时卖一价估算，无对手盘则拒单）。
 */
public record RiskConfig(int maxOpenOrdersPerAccount, BigDecimal maxOrderNotional) {
}
