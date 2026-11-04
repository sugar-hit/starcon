import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 13 利润与经营分析系统 · 利润侧域首页（`/profit`）。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而利润侧 5 个模块**全部已经交付**：多模式利润报表（13.4.1.1）/ SKU 利润（13.4.1.2）/
 * 成本结构（13.4.2.1）/ 费用差异（13.4.2.2）/ 利润预警（13.4.3.1）。
 *
 * <p>占位页上写的「SKU 维度毛利：待实施」「利润报表：待实施」全是假的 ——
 * 用户点开「利润中心」看到的是一张计划表，而真实工作区就在那里。
 *
 * <p>🔴 **为什么这一页的模块路由前缀不是 `/profit`**：蓝图 13 卷的前端入口是
 * `/analytics`（数据中心的经营总览已经占了它），利润这一侧的模块落在
 * `/analytics/profit` 下面。所以这里按**真实路由前缀**挑模块，
 * 而不是按目录名挑 —— 按目录挑会一个都挑不到。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function ProfitPage() {
  const spec = getDomainSpec('profit');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">利润中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '毛利、分润、成本归集'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/analytics/profit']} />
    </div>
  );
}
