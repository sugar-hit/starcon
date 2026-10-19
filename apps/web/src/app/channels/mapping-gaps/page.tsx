import { MappingGapWorkspace } from '@/components/channel/mapping-gap-workspace';

/**
 * 2.4.2.3 映射缺失队列 —— L3「2.4 同步运行」下的异常处理分支，锚点路由
 * `/channels/mapping-gaps`。
 *
 * <p>蓝图 02-channel-and-shop.md：「**职责**：平台商品已同步但未映射到内部 SKU 的记录，
 * 等待人工建立映射或创建新 SKU。**输入**：`listing_variant` 无映射的记录；
 * `listing_mapping_version` 的错误状态。**输出**：待映射清单（平台商品、变体、店铺、价格）；
 * 操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。**关系**：上游 = 04 刊登与内容、
 * 09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。**不按名称猜 SKU**；
 * 映射建立后回填受影响订单，历史订单冻结当时映射版本。」
 *
 * <p>为什么路由挂在 `/channels` 族下而不是 `/listings/mappings`：映射管理（4.1.2.1）
 * 是**逐变体维护映射**的视图，而本模块是**运营待办**——它的消费者是每天要清一遍缺口的人，
 * 不是改某一条映射的人。与 `/channels/sync`（同步任务）、`/channels/backfill`（增量补拉）
 * 属同一族「平台侧异常处理工作台」。
 *
 * <p>这是**真实工作区**：清单、六个筛选维度、三个操作入口全部走
 * {@code /channel/mapping-gaps} 真实端点，加载 / 空 / 错误三态齐全，
 * 不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**必带**三处明账：① 09 订单来源**未接线**（trade 侧尚未推事件）；
 * ② 价格**归 05 定价**（平台商品来源没有价格快照，不画假价格）；
 * ③ 「新建 SKU」**只登记意图**（03 侧未接线，不会真的创建 SKU）。
 */
export default function ChannelsMappingGapsPage() {
  return <MappingGapWorkspace />;
}
