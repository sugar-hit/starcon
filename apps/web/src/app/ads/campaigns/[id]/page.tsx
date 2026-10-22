'use client';

import { use } from 'react';
import { AdCampaignDetailWorkspace } from '@/components/ads/ad-campaign-detail';

/**
 * 14.1.1.2 活动详情 的路由 `/ads/campaigns/[id]`。
 *
 * <p>蓝图：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
 * 每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。
 * 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。关系：上游 = 14.1.1.1；
 * 下游 = 14.1.2.1 广告组、14.3.1.1 报表。<strong>"复制结构"是高价值操作</strong>：
 * 把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。」
 *
 * <p>🔴 页面顶部那段话不是提示语，是这一页的设计前提：
 *
 * <ol>
 *   <li><strong>平台侧修改历史当前拿不到</strong>。平台广告 API 同步器尚未交付 ⇒ 没有同步源 ⇒
 *       平台侧改历史**无法获得**。页面展示的是<strong>本地操作审计</strong>，
 *       两者在措辞上分得开 —— 把「拿不到」渲染成「暂无修改记录」就是在说「平台侧没改过」。</li>
 *   <li><strong>「复制结构」的副本是真的，平台侧还不是</strong>。复制完同时显示结构落库条数、
 *       「待回写平台」与「关键词没有复制」三件事。</li>
 *   <li><strong>曲线上的点 = 有每日指标的天</strong>。没有指标行的一天是「没数据」，
 *       不是「花了 0」。</li>
 * </ol>
 *
 * <p>与同卷的 14.1.1.1「活动列表」是一对：那边管「按维度把活动列出来并批量操作」，
 * 这一页管「这一条活动到底怎么了、接下来能动什么」。
 */
export default function AdCampaignDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  return (
    <div className="space-y-4">
      <AdCampaignDetailWorkspace campaignId={id} />
    </div>
  );
}
