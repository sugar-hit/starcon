import { WritebackBatchWorkspace } from '@/components/channel/writeback-batch-workspace';

/**
 * 2.5.2.1 常用回写操作。
 *
 * <p>蓝图 02-channel-and-shop.md：「提供高频回写的快捷入口与批量操作（批量改库存、批量改价、
 * 批量下架、批量回传物流），支持按店铺与筛选条件批量执行。」
 * 输出：「生成 N 条独立回写任务，**逐条记录结果，部分失败不影响其余**。」
 *
 * <p>**为什么这层有独立页面**：本模块的输出是运营**高频反复**执行的一组动作
 * （旺季每天批量改库存、批量回传物流），不是一段被下游消费的声明 —— 它的下游是 2.5.1.1
 * 回写任务列表，本模块是那张清单的**批量入口**。
 *
 * <p>这是**真实工作区**：候选池、试算、执行、逐条结果全部走
 * {@code GET/POST /channel/writeback-targets} 与 {@code /channel/writeback-batches} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 *
 * <p>⚠️ 页面**刻意没有**「一键重试整批」：批量不等于事务，重发要按 2.5.1.1 的任务清单逐条判断 ——
 * 对一个含「结果未知」的批次一键重发，正是本模块最容易造成资损的动作。
 */
export default function ChannelsWritebackPage() {
  return <WritebackBatchWorkspace />;
}
