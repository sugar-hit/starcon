'use client';

import { FinanceSoftwareWorkspace } from '@/components/integrations/finance-software-workspace';

/**
 * 18.2 外部系统对接 → 18.2.1.1 财务软件（卷 18 前端入口 `/integrations/connectors/finance-software`）。
 *
 * <p>蓝图：「<strong>财务软件</strong>：对接第三方财务软件（金蝶、用友、SAP、Odoo）：凭证推送、
 * 科目映射、主数据同步、余额核对；导出标准凭证文件（Excel/XML）。输入：凭证数据；科目映射配置；
 * 对账需求。输出：推送结果；凭证文件；核对差异。关系：上游 = 12.5.2.1 凭证；下游 = 外部财务软件。
 * <strong>业财一体化的常见做法是本系统管业务账、外部软件管法定账</strong>，两边通过凭证文件与科目映射衔接。」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）<strong>不是一件事</strong>：
 * 那一页管的是「谁可以接入我们」，本页管的是「凭证怎么交给外部财务软件」。
 * 两者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的财务软件适配器</strong>，所以「直连推送」这一格在页面上
 * 如实显示为「未接入」，归 18.2 卷：页面**不提供**这个按钮，也不做本地兜底生成 ——
 * 一个点了必然 409 的按钮会让运营以为「点一下就能推」。与外部软件有没有直连通道，
 * 不影响下面三件<strong>本系统内部能力</strong>的真实生效：科目映射、主数据映射、
 * 标准凭证文件导出（CSV / Excel / XML 真文件 + sha256 留痕）、余额核对差异
 * （真读本地试算平衡表 × 导入的外部余额，逐科目算差异）。
 */
export default function FinanceSoftwarePage() {
  return (
    <div className="space-y-4">
      <FinanceSoftwareWorkspace />
    </div>
  );
}
