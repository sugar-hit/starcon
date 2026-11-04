import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 06 采购与供应系统 · 采购侧域首页（`/purchase`）。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而采购侧 20 个模块**全部已经交付**：缺货与断货风险 / 补货建议 / 采购计划 / 采购申请 /
 * 需求合并与批量 / 采购单列表 / 编辑采购单 / 新建采购单 / 采购单审批 / 跟单与交期 /
 * 到货与逾期 / 加工单 / 质检单 / 不良品处理 / 采购对账 / 采购分析 / 开发立项 /
 * 打样与样品管理 / 试产单 / 转量产与建档。
 *
 * <p>占位页上写的「采购单管理：待实施」「到货质检：待实施」全是假的 ——
 * 用户点开「采购中心」看到的是一张计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function PurchasePage() {
  const spec = getDomainSpec('purchase');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">采购中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '采购需求、采购单、1688 档口直连'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/purchase']} />
    </div>
  );
}
