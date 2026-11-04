import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 08 仓储作业系统 · 域首页。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而这个域下面的 21 个模块**全部已经交付**：仓库列表 / 仓库详情 / 仓库配置 / 库位树 /
 * 库位自动分配 / 收货单 / 收货差异 / 上架任务 / 待检处理 / 出库单列表 / 出库单详情 /
 * 拣货任务 / 波次与合并拣货 / 复核扫描 / 打包与称重 / 发货交接 / 组装单 / 拆解单 /
 * 包裹详情 / 库位利用率 / 出库时效。
 *
 * <p>占位页上写的「入库作业：待实施」「库内作业：待实施」全是假的 ——
 * 用户点开「仓储中心」看到的是一张计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function WarehousePage() {
  const spec = getDomainSpec('warehouse');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">仓储中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '仓库档案、仓位、库内作业'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/warehouse']} />
    </div>
  );
}
