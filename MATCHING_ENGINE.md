# 内存交易撮合引擎 · 撮合规则、确定性保证与验证方法

一套本地可运行的内存交易撮合与成交回放流程：多账户限价/市价撮合、撤单/改单、
并发委托与限额校验、快照导出与确定性回放。相同输入恒得相同成交序列与资产余额，
可从快照完整恢复，重复回放不产生重复成交。

## 1. 模块结构

```
matching/
├── model/      领域模型：Command(指令) / Order(委托) / Account(账户) /
│               Trade(成交) / Rejection(拒绝) / 枚举(方向/类型/状态/原因)
├── engine/     MatchingEngine（撮合+资金结算+风控+快照）与 OrderBook（价格/时间优先）
├── gateway/    DeterministicGateway（多账户并发提交，唯一消费者按 seq 全序应用）
├── replay/     ReplaySupport（按 seq 回放，已应用的指令自动跳过）
├── snapshot/   Snapshot（确定性快照结构）
├── util/       JsonIO（Jackson，固定键序的快照 JSON 往返）
└── demo/       MatchingDemo（本地 end-to-end 演示 main）
```

引擎本身**不依赖 Spring**，是纯 JDK 内存组件；外层工程仍是 Spring Boot 应用。

## 2. 撮合规则

### 2.1 委托类型与生命周期

| 类型 | 语义 |
| --- | --- |
| 限价单 LIMIT | 价格 `p`、数量 `q`。可及范围内立即吃单，余量**冻结资金/持仓后挂簿** |
| 市价单 MARKET | IOC：按对手挂单立即成交，未成交余量**立即撤销**，永不进入订单簿 |

委托状态机：`NEW → PARTIALLY_FILLED → FILLED`；任意 live 状态可 `CANCELED`；
未通过校验的委托为 `REJECTED`（**从未进入订单簿**）。
数量自洽恒等式：`totalQty = filledQty + canceledQty + openQty`。

### 2.2 价格优先、时间优先

订单簿用 `TreeMap<价格, FIFO 队列>` 维护：

* 买单从**高到低**、卖单从**低到高**扫描对手盘（价格优先）；
* 同一价位严格按“下单 seq”的 FIFO 排队，先到先成交（时间优先）；
* 成交价恒取**挂单价（maker price）**；
* 改单 = 撤旧余量 + 以同 `clientOrderId` 重建委托并**重新参与排队**，
  保留 `id` 与 `filledQty` 审计轨迹，但**丢失原时间优先**。

### 2.3 资金/持仓模型与守恒

账户四个分量：`cash / reservedCash`、`position / reservedPosition`，恒有

```
cash     = availableCash     + reservedCash        （均 ≥ 0）
position = availablePosition + reservedPosition    （均 ≥ 0）
```

* 限价单**为挂簿余量冻结**：买方冻结 `price×openQty` 现金，卖方冻结 `openQty` 持仓；
  成交逐笔消耗冻结并交割，撤单/改单按余量解冻。
* 市价单不预冻结，逐价位按可用现金/持仓**裁剪可成交量**（现金不足向下取整），
  从不错误扣划、绝不负余额、绝不超卖。
* 全局守恒：成交只是现金与持仓在买方/卖方间转移，Σcash、Σposition 在撮合前后不变
  （测试中逐场景断言）。

### 2.4 拒绝原因（可区分，显式拒绝而非静默丢弃）

| 原因 | 触发条件 |
| --- | --- |
| `INVALID_QUANTITY` | 数量 ≤ 0；改单新总量 ≤ 已成交量 |
| `INVALID_PRICE` | 限价 ≤ 0；对市价单改价 |
| `UNKNOWN_ORDER` | 撤/改引用不存在或已终结（FILLED/CANCELED/REJECTED）的委托，或委托属于他人 |
| `SELF_TRADE` | 在可成交价位范围内存在同账户对手挂单（提交前预检 + 撮合时双保险） |
| `INSUFFICIENT_CASH` | 买入可用现金不足以覆盖最坏情况下的挂簿冻结 |
| `INSUFFICIENT_POSITION` | 卖出可用持仓不足以覆盖挂簿余量 |
| `LIMIT_EXCEEDED` | 单账户在途委托数超上限，或名义金额超单笔上限（市价按当下最优对手价估算） |
| `MARKET_NO_LIQUIDITY` | 市价单进入时无任何对手盘 |
| `DUPLICATE_COMMAND` | 同 seq 重复应用 / 同批重复 seq / clientOrderId 重复 |
| `INVALID_COMMAND` | 缺字段、未知账户等指令级错误 |

### 2.5 自成交预防

下单/改单在**入簿前**扫描价格可及范围内的对手挂单，一旦存在同账户 live 挂单，
整单以 `SELF_TRADE` 拒绝；撮合循环中再做一次同账户跳过（双保险，覆盖快照恢复场景）。

## 3. 确定性保证

确定性来自“**单一全序 + 单线程状态变更**”，而不是来自线程调度：

1. 每条 `Command` 带全序序号 `seq`；空引擎首条指令自动锚定起点（也可 `prime(startSeq)`
   显式声明）。引擎 `apply` 要求 seq 严格连续递增。
2. `DeterministicGateway` 让多账户工作线程（虚拟线程）在同一栅栏后并发提交、乱序到达；
   但只有**一个撮合消费者**严格按 seq 升序调用 `engine.apply`，并用
   `CompletableFuture` 把结果回填给对应提交线程。
   因此无论 JVM 怎样调度、是否用不同种子打乱派发顺序，引擎看到的永远是同一条全序，
   成交编号/价格/量、账户四分量、拒绝集合**逐项一致**（测试用完整指纹比对）。
3. 所有状态变更在引擎内部同一把 `guard` 锁内完成；撮合过程不读取时钟、随机数等
   非确定输入，`TreeMap/LinkedHashMap` 保证遍历与序列化键序固定。
4. 幂等：`seq ≤ lastSeq` 的指令直接以 `DUPLICATE_COMMAND` 拒绝且不触碰状态，
   因此同一事件日志可安全重放；`ReplaySupport.replay` 对已应用指令**跳过**（连拒绝都不
   新增），重复回放任意次结果恒等。

## 4. 快照与回放

`MatchingEngine.snapshot()` 导出完整覆盖状态的 `Snapshot`：

* 水位 `lastSeq`、`nextOrderId`、`nextTradeId`；
* 账户四个分量；各标的订单簿（价位降序买盘/升序卖盘，含每笔挂单的 filled/canceled/status）；
* 全部历史成交、全部拒绝记录、全部委托（**含已终结**，保证恢复后 UNKNOWN_ORDER 判定一致）。

恢复路径：`new MatchingEngine()` → `restore(snapshot)`（空引擎直接恢复，无需预注册账户）
→ 对 `seq > lastSeq` 的增量日志 `ReplaySupport.replay`。
`JsonIO` 基于 Jackson 固定配置，同一状态两次导出**逐字节相同**，可落盘、可 diff。

## 5. 验证方法

### 5.1 运行全部测试

```bash
mvn test
```

共 17 个撮合测试用例（另含原 Spring 上下文冒烟），分四组：

* **MatchingEngineTest（8）**：部分成交余量自洽、价格/时间优先、撤单解冻、
  改单保留已成交且丢失时间优先、各类拒绝原因区分、风控限额、市价 IOC 与无流动性、
  市价买入现金不足时的裁剪与非负余额。
* **ConcurrentGatewayTest（2）**：6 账户 × 12 指令在 3 种不同并发调度
  （shuffle 种子 1 / 2 / 顺序）下成交序列、余额、拒绝**指纹完全一致**，
  并断言现金/持仓恒等式、全局 Σcash/Σposition 守恒、无重复成交；
  以及同批重复 seq 与超限委托的显式拒绝。
* **SnapshotReplayTest（4）**：快照 JSON 往返逐项一致且序列化逐字节可复现；
  整段日志重复回放不新增成交/拒绝；快照 + 增量回放 == 从头执行；
  部分成交挂单经快照恢复后续撮的状态自洽。
* **ReplaceEdgeCaseTest（3）**：买单改价到可成交时从冻结现金即时结算且无超扣、
  改量超过可用持仓时整单拒绝且旧单价格/冻结完整保留并仍可成交、
  改单造成自成交时回滚旧单且零成交。

单测日志按 `【输入】→【判定】→ 成交明细 / 【余额】→【结果】` 打印每条命令、
拒绝原因明细与判定依据，便于人工核对。

### 5.2 运行端到端演示

```bash
mvn compile exec:java
```

`MatchingDemo` 依次演示：限价部分成交 → 市价 IOC（现金不足裁剪）→ 改单 →
5 条多账户指令并发乱序（含成交、撤单、超限拒绝）→ 快照导出 JSON →
空引擎恢复 → 整段日志重复回放，最后对比两台引擎成交数/拒绝数/余额完全一致。

## 6. 边界与约定

* 数量为正整数 `long`；价格/金额为 `BigDecimal`，用 `compareTo` 比较，
  成交金额按挂单价 × 成交量即时计算。
* seq 必须连续；乱序到达由网关缓冲排序，直接对引擎 `apply` 跳号会快速失败
  （IllegalStateException）而非产生未定义结果。
* 市价单的风控名义金额按下单瞬间最优对手价估算；没有对手盘直接拒绝。
