'use client';

import { KnowledgeWorkspace } from '@/components/crm/knowledge-workspace';

/**
 * 15.4.2.1 客服知识库（蓝图 15 卷 L3 锚点 15.4.2 下的知识库子域）。
 *
 * <p>路由是 `/crm/knowledge`，与 `/crm`（CRM / 客服域首页）**并列**而不是挂在 `/aftersales` 下：
 * 本模块的上游是 3.4.2.2 文案资产、下游是 15.1.1.2 发送与 15.5.1 自动回复，
 * 三者都与「售后工单」这一实体无关。
 *
 * <p><b>与 `/aftersales/knowledge`（11.4.2.2）不是同一个模块</b>，页面上必须讲清的四条：
 *
 * <ul>
 *   <li><b>组织维度不同。</b>本模块按<b>店铺 / 类目 / 语言</b>组织 ——
 *       回答「这家店这个类目中文版此刻该给买家哪一条答法」；
 *       11.4.2.2 按<b>平台规则版本</b>组织 —— 回答「这个平台政策这一版变了没有」。
 *       两者连服务都不同（本页是 crm 服务 8086，那边是 catalog 服务 8082）。</li>
 *   <li><b>版本机制不同。</b>本页的版本带<b>半开生效区间 {@code [from, to)}</b>，
 *       重叠一律拒绝不做自动裁决，已发布版本只能向前关闭 ——
 *       与 catalog V23 / V25 / V95 的 R1–R5 同一口径。</li>
 *   <li><b>「平台改版后定位过期内容」是一条查询，不靠人工翻。</b>
 *       填平台 + 新政策生效时刻，一次列出「当前生效版写于改版之前、且至今仍无接替版本」的条目，
 *       并把判定口径原文显示出来。</li>
 *   <li><b>引用记录是只读的审计事实。</b>谁在什么时候引用了哪一版真实落库，
 *       但刻意不提供修改 / 删除入口。</li>
 * </ul>
 */
export default function CrmKnowledgePage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客服知识库</h1>
        <p className="text-muted-foreground text-copy-13">
          维护常见问题标准答法、平台政策说明、产品使用指导与退换货规则，
          <strong>按店铺 / 类目 / 语言组织</strong>，支持版本与生效日期。 每条知识都标注
          <strong>适用平台</strong>与<strong>生效时间</strong>：
          平台政策改版时，一次查询就能列出内容仍停留在旧口径、还没人改过的条目。 生效区间是
          <strong>半开区间 [from, to)</strong> —— 终点那一刻旧版本就失效，
          重叠一律拒绝不做自动裁决，已发布版本只能向前关闭。
        </p>
      </header>

      <KnowledgeWorkspace />
    </div>
  );
}
