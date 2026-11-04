import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 05 定价与价目系统 · 域首页。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 一张静态功能清单，
 * 上面写着「汇率源与刷新策略：待实施」「促销与折扣引擎：待实施」，
 * 而这个域下面的 13 个模块**全部已经交付**：价目表、优先级与冲突检测、成本构成、
 * 汇率查询、平台费用、利润测算器、定价模板、价格规则、调价单、批量调价、
 * 平台价格同步、价格历史、异常告警。
 *
 * <p>也就是说，那张占位页上写的每一句「待实施」都是假的 ——
 * 用户点开「定价中心」看到的是一个计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function PricingPage() {
  const spec = getDomainSpec('pricing');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">定价中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '多币种价格、汇率、定价策略与平台费率模型'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/pricing']} />
    </div>
  );
}
