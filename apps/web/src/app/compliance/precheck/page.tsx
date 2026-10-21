'use client';

import { CompliancePrecheckWorkspace } from '@/components/compliance-precheck/compliance-precheck-workspace';

/**
 * 合规预检：L3「16.2.2 商品合规」下的 16.2.2.1。
 *
 * <p>蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
 * 标签与说明书要求，多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。
 * 输出：体检报告（分维度得分 + 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
 * <strong>预检在保存草稿时就跑一次</strong>（即时反馈），<strong>发布时再跑一次</strong>（强制校验）。」
 *
 * <p>🔴 本页与 4.2.3.2「合规预检（违禁词 / 侵权）」是<strong>两个模块</strong>：那边在
 * {@code /listings/compliance}（卷 04，发布前的风险清单：阻断项 + 提醒项），
 * 本页在 {@code /compliance/precheck}（卷 16，体检报告：分维度得分 + 问题清单 + 整改建议）。
 * 两边不共用表、不共用端点、不共用页面。
 *
 * <p>🔴 页面上<strong>没有</strong>「拦截 / 豁免 / 审批」按钮：那归 16.2.1.2 拦截执行（未交付）。
 * 本页只出报告与结论；「发布前的强制校验」在这里是一次体检，不是一次放行决定。
 */
export default function CompliancePrecheckPage() {
  return (
    <div className="space-y-4">
      <CompliancePrecheckWorkspace />
    </div>
  );
}
