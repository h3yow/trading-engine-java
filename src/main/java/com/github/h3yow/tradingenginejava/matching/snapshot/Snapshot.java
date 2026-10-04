package com.github.h3yow.tradingenginejava.matching.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎状态快照（确定性数据结构：LinkedHashMap 保证序列化键序稳定）。
 *
 * <p>快照完整覆盖：已处理指令水位 {@code lastSeq}、编号水位、账户四个分量、
 * 各标的订单簿（含每笔挂单的已成交量与状态）、全部历史成交、全部拒绝记录、
 * 以及全部委托（含已终结，保证恢复后 UNKNOWN_ORDER 判定一致）。
 * 快照 + lastSeq 之后的增量事件日志即可在任意空引擎上完整恢复，重复恢复不产生重复成交。
 */
public final class Snapshot {
    /**
     * 快照格式版本。当前为 1；旧版本导出的 JSON 没有该字段，
     * 反序列化后取默认值 0，恢复时按 v1 语义兼容处理（旧导出继续可用）。
     */
    public int formatVersion = CURRENT_FORMAT_VERSION;
    public long lastSeq;
    public long nextOrderId;
    public long nextTradeId;
    public Map<String, Object> accounts = new LinkedHashMap<>();
    public Map<String, Object> symbolBooks = new LinkedHashMap<>();
    /** {"count": n, "list": [trade...]}，保留显式计数便于人工核对。 */
    public Map<String, Object> trades = new LinkedHashMap<>();
    public List<Object> rejections = new ArrayList<>();
    public List<Object> orders = new ArrayList<>();

    /** 当前快照格式版本号。 */
    public static final int CURRENT_FORMAT_VERSION = 1;
}
