import { AdDaypartWorkspace } from '@/components/ads/ad-dayparts';

/**
 * 14.2.1.2 分时策略。
 *
 * <p>蓝图：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
 * 支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。
 * 输出：分时策略配置 + 定时调整任务。关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
 * <strong>分时策略需按店铺时区配置</strong>，跨时区店铺用错时区会完全失效。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>时区是命门</strong>。「按小时」= <strong>店铺本地时区</strong>的小时，
 *       不是 UTC 小时。店铺时区来自 channel 的<strong>站点参数</strong>
 *       （shop_marketplace → marketplace.default_timezone），不是硬编码时区表，
 *       也不是「所有店铺同一时区」。启用多个站点且时区不同、或没启用站点的店铺
 *       <strong>定不了时区</strong> ⇒ 它们的策略<strong>不许激活</strong>，
 *       页面上单列成「未能按店铺本地时区归属」，<strong>不摊给任何默认时区</strong>。</li>
 *   <li><strong>夏令时</strong>由 IANA 时区 + PostgreSQL 的 tzdata 负责 ——
 *       固定 UTC 偏移会冬夏整整差一小时，分时策略会整体错位。</li>
 *   <li><strong>「停投」= 预算系数置 0</strong>（该小时不再花钱），
 *       <strong>不是</strong>把活动状态改成暂停：活动状态是活动级的，
 *       表达不了「只在 15:00 停投」，而且会覆盖人工暂停。</li>
 *   <li><strong>不复利</strong>：目标值 = <strong>基准</strong> × 系数
 *       （基准快照一次），不是当前值 × 系数 —— 后者一天下来能把预算烧穿。</li>
 *   <li><strong>定时</strong>在外部调度里：本服务不装 @Scheduled，
 *       K8s CronJob 按 run_interval_minutes 调运行入口。</li>
 * </ol>
 */
export default function AdDaypartPage() {
  return <AdDaypartWorkspace />;
}
