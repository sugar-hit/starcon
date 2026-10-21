'use client';

import { ReviewRuleWorkspace } from '@/components/crm/review-rule-workspace';

/**
 * 15.2.2.2 索评规则（蓝图 15 卷 L3 锚点 15.2.2 下的索评**配置**子域）。
 *
 * <p>路由是 `/crm/review-rules`，与 `/crm/review-requests`（15.2.2.1 索评执行）
 * 并列且**刻意不共用命名空间**：一个管「策略怎么配」，一个管「这一单要不要索评」。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>生效策略只有一个答案</b>：同一范围同一优先级不允许有两条生效规则（数据库层挡住），
 *       命中时范围越具体越优先。页面**不提供**「指定用哪条规则」的入口 ——
 *       指定规则就绕过了「生效策略」这件事本身。</li>
 *   <li><b>节假日按规则时区换算</b>：用 UTC 日界会把「东八区 10-02 00:00」算成 10-01，
 *       节假日就错开一天。页面显示顺延后的排期与命中的节假日，并说清顺延口径。</li>
 *   <li><b>A/B 是确定性的</b>：同一个种子永远落到同一个臂，所以效果对比可复算。
 *       没有种子时页面只说权重分布，**不**挑一个臂显示。</li>
 *   <li><b>下游 15.1.1.2 消息发送未交付</b>：本模块只**产出**话术与生效策略，**不发送**，
 *       页面**不提供**「已发送」按钮 —— 一个点了就返回成功的假按钮会让运营以为
 *       买家已经被索评过，而过度索评的代价正是买家投诉与平台处罚。</li>
 * </ul>
 */
export default function CrmReviewRulesPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">索评规则</h1>
        <p className="text-muted-foreground text-copy-13">
          配置索评策略：按<strong>店铺</strong>、按<strong>物流方式</strong>（自发货 / 平台仓）
          设置不同延后时间，<strong>避开节假日</strong>，支持 <strong>A/B 测试不同话术</strong>。
          输出是<strong>生效策略</strong>与<strong>效果对比</strong>。
          下游 15.1.1.2 消息发送未交付，本页只产出话术与策略，<strong>不发送</strong>。
        </p>
      </header>

      <ReviewRuleWorkspace />
    </div>
  );
}
