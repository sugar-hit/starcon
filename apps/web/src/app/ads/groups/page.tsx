import { AdGroupsWorkspace } from '@/components/ads/ad-groups';

/**
 * 14.1.2.1 广告组管理。
 *
 * <p>蓝图：「管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
 * 输入：广告组设置；关联商品选择（从 4 listing 中选）。
 * 输出：广告组记录 + 组级报表入口。
 * 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
 * <strong>广告组与 SKU 的映射是利润归因的基础</strong>——投放的是 listing，归因要落到内部 SKU。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>组级表现当前拿不到</strong>。现有事实表里<strong>没有组级指标表</strong>
 *       （`ad_campaign_daily_metric` 是 campaign 维度，没有 group_id）⇒ 组级指标
 *       {@code available=false}。页面显示「拿不到 + 责任模块」，<strong>不拿活动级指标冒充
 *       组级指标</strong> —— 那是假事实，比缺功能严重得多。</li>
 *   <li><strong>组级报表入口是入口，不是报表</strong>。入口已交付（前端路由标识 + 责任模块
 *       都写在页面上），报表本体归 <strong>14.3.1.2 广告组与词报表</strong>（同批并行开发，
 *       下一批并入集成头）。</li>
 *   <li><strong>「本地已改」与「平台侧已改」分得开</strong>。平台回写队列未交付 ⇒ 所有本地写
 *       只落本地并标「待回写平台」；界面上的「已暂停」<strong>不等于</strong>平台侧已暂停
 *       —— 那是在烧钱的假象。</li>
 *   <li><strong>定向与匹配类型是平台原文</strong>（14.1.3.2 未交付 ⇒ 不解读）。页面只原样展示，
 *       不给「建议」，也不把两个字段混成一个。</li>
 * </ol>
 *
 * <p>与 14.1.1.2 活动详情是一对：那边给活动的全景与结构的读，本页给广告组自己的
 * 日常管理（新建一组、改一组设置、设这一组的投放商品）。
 */
export default function AdGroupsPage() {
  return <AdGroupsWorkspace />;
}
