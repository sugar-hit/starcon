import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 06 采购与供应系统 · 供应侧域首页（`/supply`）。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而供应侧 6 个模块**全部已经交付**：供应商列表 / 供应商详情 / 供应商评级 /
 * 档口与货源池 / 档口详情 / 同款货源推荐 / 供应商资质。
 *
 * <p>占位页上写的「供应商档案：待实施」「1688 / 档口对接：待实施」全是假的 ——
 * 用户点开「供应中心」看到的是一张计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function SupplyPage() {
  const spec = getDomainSpec('supply');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">供应中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '供应商档案、评分、分级管理'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/supply']} />
    </div>
  );
}
