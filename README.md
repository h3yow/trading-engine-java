# 内存交易撮合与成交回放引擎（trading-engine-java）

一个**本地可运行**的内存撮合引擎：多账户、限价单 / 市价单、撤单 / 改单、
价格优先 + 时间优先撮合、资产限额校验、订单簿快照导出与**确定性回放**。
核心目标：**同一批委托在任意并发调度、重复回放、快照恢复等不同路径下，
成交序列与资产余额完全一致、可复现、可解释。**

- Java 21 + Spring Boot 3.4（HTTP 仅为可选入口，撮合核心不依赖 Spring）
- 无外部存储：订单簿、账本、成交全部在内存
- 金额与数量一律使用 `long`（整数，避免浮点误差）

## 构建与运行

```bash
# 编译 + 全部单测
mvn test

# 端到端演示（打印输入、判定依据、成交、余额，以及三条路径的一致性结论后退出 web 容器需手动停止）
mvn package -DskipTests
java -jar target/trading-engine-java-1.0-SNAPSHOT.jar --spring.profiles.active=demo

# 启动 HTTP 服务（默认 8080）
mvn spring-boot:run
```

HTTP 手工验证：

```bash
# 提交一批命令（body 为 Command JSON 数组）
curl -s localhost:8080/api/commands -H 'Content-Type: application/json' -d '[
  {"seq":1,"type":"DEPOSIT","accountId":"A","symbol":"BTC","depositCash":1000000,"depositQty":0},
  {"seq":2,"type":"PLACE","accountId":"A","orderId":"b1","symbol":"BTC",
   "side":"BUY","orderType":"LIMIT","price":100,"qty":10}
]'
# 查看快照（账户余额、全部订单、活动订单、成交历史、续号位点）
curl -s localhost:8080/api/snapshot
```

## 模型与命令

| 类型 | 说明 |
|---|---|
| `Command.deposit(seq, account, cash, symbol, qty)` | 开户 / 入金 / 划入证券 |
| `Command.place(seq, account, orderId, symbol, side, orderType, price, qty)` | 下限价单或市价单 |
| `Command.cancel(seq, account, orderId)` | 撤单（仅 NEW / PARTIALLY_FILLED 可撤） |
| `Command.modify(seq, account, orderId, orderType, price, qty)` | 改单（新总量必须 > 已成交量） |

每条命令返回 `CommandResult`：`accepted`、可区分的 `reason`（拒绝时）、
人类可读的 `message`（判定依据）、以及本命令产生的成交号数组。

## 撮合规则

1. **价格优先、同价时间优先**：买单按价格从高到低、卖单从低到高；同价位按命令 `seq` 从早到晚。
2. **成交价 = maker（被动挂单）价**。
3. **限价单**：可成交范围内逐档吃单，剩余量留在订单簿；买入按下单金额预冻现金，
   卖出按数量预冻持仓，成交逐笔核销，撤单 / 市价剩余撤销时释放剩余冻结。
4. **市价单**：不挂簿、不预冻；买入逐档检查可用现金（买不起整档就买得起多少买多少），
   全部吃完或现金不足后，剩余量撤销并记录停止原因（`NO_LIQUIDITY` / `INSUFFICIENT_CASH`）；
   下单时若完全没有对手流动性，直接以 `NO_LIQUIDITY` 拒绝。
5. **改单**：摘簿 → 释放旧条款剩余冻结 → 以新条款重新做自成交与限额校验 → 立即参与撮合；
   改单后以**新 seq 排到同价位队尾**（失去原时间优先级）；任何校验失败都会回滚到旧条款并重新挂簿。
6. **状态机自洽**：
   `NEW → PARTIALLY_FILLED → FILLED`；`NEW/PARTIALLY_FILLED → CANCELLED`；
   校验未通过的委托不产生订单，直接返回拒绝。

### 拒绝原因（`RejectReason`，可按原因区分）

| 原因 | 触发条件 |
|---|---|
| `INVALID_QUANTITY` | 数量 ≤ 0、改单新总量 ≤ 已成交量、入金金额非正等 |
| `INVALID_PRICE` | 限价价格 ≤ 0 |
| `UNKNOWN_ACCOUNT` | 委托账户不存在（未先 DEPOSIT 开户） |
| `UNKNOWN_ORDER` | 撤单 / 改单的委托不存在或不属于该账户 |
| `ORDER_NOT_ACTIVE` | 对已成交 / 已撤销订单再撤改 |
| `DUPLICATE_ORDER_ID` | orderId 重复 |
| `SELF_TRADE` | 委托会与**同账户**任一可成交对手单成交（吃单前整单拒绝） |
| `INSUFFICIENT_CASH` | 限价买入（或改单）所需冻结现金超过可用现金 |
| `INSUFFICIENT_POSITION` | 卖出所需冻结持仓超过可用持仓 |
| `NO_LIQUIDITY` | 市价单无对手流动性 |
| `INVALID_SEQUENCE` | seq 未严格递增、同批重号 |

## 确定性保证

1. **单一锁串行**：`MatchingEngine` 所有状态变更在引擎实例的同一把锁内完成，
   撮合是“多命令 → 单串行历史”，不存在交叉撮合产生的中间态。
2. **seq 是唯一排序键**：`submitBatch` 先把整批命令按 `seq` 升序排序、批内重号拒绝，
   再逐条执行。调用方的提交顺序、线程调度顺序**不影响**任何结果。
3. **并发提交收集器**：多线程场景使用 `engine.newCollector()`：线程并发 `add`
   （只入无锁队列、不触碰引擎状态），全部提交后单线程 `finish()`，
   引擎在锁内对**全量命令**统一排序执行 —— 这保证“同一批委托不同并发调度”结果一致。
4. **成交号单调**：`tradeSeq` 由引擎单调分配；快照保存 `nextTradeSeq`，
   恢复后续号不重用，杜绝重复成交。
5. **快照只载状态、不重放历史**：`restore(snapshot)` 恢复订单簿、账本（含冻结）、
   历史成交与序号位点；从快照继续只执行新命令，因此不会重复成交、不会状态漂移。
6. **可复现**：`ReplayEngine.replay(commands)` 对同一命令日志的任意次执行结果逐字节等价
   （账户、订单、成交、序号位点全部相同）。

## 验证方法（测试）

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `MatchingEngineTest` | 价格 / 时间优先、部分成交与剩余冻结、撤单解冻、改单与非法改单回滚、
  全部 10 类拒绝原因区分、自成交整单拒绝、市价单逐档与现金不足撤销、
  现金 / 证券总量守恒、无负余额、成交号不重复 |
| `ConcurrentDeterminismTest` | 8 线程 × 30 轮重复的并发收集器提交，指纹与顺序基线一致；
  12 个独立引擎各自乱序回放同一批命令，指纹唯一；共享引擎高并发压力下不丢单、不超卖；
  未知账户 / 现金超限 / 持仓超限委托均被**明确拒绝** |
| `ReplayDeterminismTest` | 同一日志重复 3 次回放指纹相同；快照 JSON 序列化往返等价；
  中途快照恢复后续跑与一次性完整回放一致；恢复后旧 seq 被拒（`INVALID_SEQUENCE`）、
  新成交号从 `nextTradeSeq` 续号 |

所有测试在 INFO 日志中打印**输入命令与判定依据**（接受的成交明细 / 拒绝原因与 basis），
可用以下方式查看完整日志：

```bash
mvn test -Dtest=MatchingEngineTest
cat target/surefire-reports/com.github.h3yow.tradingenginejava.*.txt
```

### 手工核对守恒的要点

- 现金只在买方 ↔ 卖方之间转移：`Σ cash` 恒定（`reservedCash` 是 `cash` 的子集，不是额外金额）。
- 证券只在卖方 ↔ 买方之间转移：`Σ positions` 恒定（`reservedPositions` 是 `positions` 的子集）。
- 任意时刻：`cash ≥ reservedCash ≥ 0`、`position ≥ reservedPosition ≥ 0`。
- 每笔成交中 maker 与 taker 的成交量相等、双方按同一价格收付，冻结在成交前精确核销。

## 代码结构

```
src/main/java/com/github/h3yow/tradingenginejava/
├── model/       Side / OrderType / OrderStatus / RejectReason / Order / Trade / CommandType
├── command/     Command（命令日志条目） / CommandResult
├── account/     Account（现金与持仓账本，冻结/释放/成交结算）
├── engine/      OrderBook（价格+时间优先） / MatchingEngine（撮合+校验+快照）
│                / ReplayEngine（确定性回放与快照续跑） / EngineSnapshot
├── api/         TradingController（可选 HTTP 入口）
└── demo/        DemoRunner（端到端演示）
```
