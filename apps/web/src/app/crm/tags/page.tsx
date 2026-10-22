'use client';

import { CustomerTagWorkspace } from '@/components/crm/customer-tag-workspace';

/**
 * 15.3.2.1 客户标签（蓝图 15 卷 L3 锚点 15.3.2 下的客户标签子域）。
 *
 * <p>路由是 `/crm/tags`，与 `/crm/knowledge`（15.4.2.1 客服知识库）、
 * `/crm/emails`（15.1.2.1 买家邮件）、`/crm/review-requests`（15.2.2.1 索评）、
 * `/crm/review-rules`（15.2.2.2 索评规则）**并列**：
 * 本模块的上游是 9 订单与 11 售后、下游是 15.3.1.1 筛选 / 14.4 促销定向 / 15.5.2 关怀邮件，
 * 与「索评 / 知识」不是一件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>「退货率高」这类标签要谨慎使用</b>：样本不足时结论是
 *       <strong>不可判定</strong>而不是「是」；必须显著高于租户基线；
 *       可争议标签<strong>必须带原因</strong>，而且可以被人工复核与纠正。</li>
 *   <li><b>标签不触发任何动作</b>：不自动屏蔽、不自动拒绝、不自动降权。
 *       下游的客户筛选（15.3.1.1）、促销定向（14.4）、关怀邮件（15.5.2.1）
 *       都<strong>未交付</strong>，页面不提供任何看起来能点的假入口。</li>
 *   <li><b>人工与自动分得清</b>：同一枚标签，人工打的那条与规则打的那条是两条记录 ——
 *       运营要知道这枚标签是谁给的，自动重跑也绝不覆盖人工标的那条。</li>
 *   <li><b>判定依据看得见</b>：每条自动标签都能展开看「依据哪几笔订单 / 哪几张售后单」。</li>
 * </ul>
 */
export default function CrmTagsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客户标签</h1>
        <p className="text-muted-foreground text-copy-13">
          维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），支持
          <strong>按订单行为自动打标</strong>与<strong>手工打标</strong>。
          <strong>「退货率高」这类标签要谨慎使用</strong>
          ，避免对正常买家产生歧视性动作 —— 所以样本不足时结论是
          <strong>不可判定</strong>而不是「是」，必须显著高于租户基线，
          而且每一条都带原因、可以被人工复核与纠正。
        </p>
      </header>

      <CustomerTagWorkspace />
    </div>
  );
}
