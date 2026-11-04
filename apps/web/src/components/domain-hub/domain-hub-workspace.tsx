'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { ArrowRight, Boxes } from 'lucide-react';
import { Badge, EmptyState, SectionCard } from '@starcon/ui';
import { DOMAIN_MODULES, type DomainModule } from '@/lib/domain-modules.generated';

/**
 * 域首页工作区 —— 用**真实存在的模块页面**撑起一个域的首页。
 *
 * <p>🔴 这个组件存在的理由：域首页此前挂的是一个「域占位页」（`DomainPlaceholder`）——
 * 一张静态功能清单 + 上下游文字，**一条业务数据都没有**，而域下面真实的模块页面
 * （物流 26 个、CRM 24 个、仓储 21 个……）就躺在同一个目录里。
 *
 * <p>用户点开「物流中心」看到的是一张「功能待实施」的计划表，
 * 而实际上承运商、运单、轨迹、报关、成本分析全都已经交付了 ——
 * 那张占位页说的每一句话都是假的。
 *
 * <p>所以这里列的东西**全部来自真实文件系统**：
 * 清单由 `tools/codegen/build-domain-modules.mjs` 扫 `apps/web/src/app` 生成，
 * 模块号与模块名取自每个页面**自己写的文档注释**，分组名取自功能蓝图原文。
 * 本组件**不产出任何业务数据**，也没有 KPI 数字 —— 它只是一张目录。
 *
 * <p>没有真实模块时如实说空，不画一张假的卡片列表充数。
 */

export interface DomainHubWorkspaceProps {
  /** 路由前缀：模块住在哪个路由前缀下（如 `['/pricing']`）。 */
  prefixes: string[];
}

/** 一个模块按 L2 归组后的结果。 */
interface HubSection {
  id: string;
  name: string;
  groups: { id: string; name: string; modules: DomainModule[] }[];
}

/**
 * 按路由前缀挑出真实模块，并按蓝图的 L2 / L3 归组。
 *
 * <p>前缀匹配而不是域名匹配：利润中心的模块住在 `/analytics/profit` 下
 * （13.4 利润报表 / 13.4.1.2 SKU 利润 / 13.4.3.1 利润预警……），
 * 按域名挑会一个都挑不到。
 */
export function collectHubSections(prefixes: string[]): HubSection[] {
  const all = Object.values(DOMAIN_MODULES).flat();
  const picked = all.filter((entry) =>
    prefixes.some((prefix) => entry.href === prefix || entry.href.startsWith(prefix + '/')),
  );

  const sections = new Map<string, HubSection>();
  for (const entry of picked) {
    let section = sections.get(entry.sectionId);
    if (!section) {
      section = { id: entry.sectionId, name: entry.sectionName, groups: [] };
      sections.set(entry.sectionId, section);
    }
    let group = section.groups.find((g) => g.id === entry.groupId);
    if (!group) {
      group = { id: entry.groupId, name: entry.groupName, modules: [] };
      section.groups.push(group);
    }
    group.modules.push(entry);
  }

  return [...sections.values()].sort((a, b) => a.id.localeCompare(b.id, 'en'));
}

export function DomainHubWorkspace({ prefixes }: DomainHubWorkspaceProps) {
  const sections = useMemo(() => collectHubSections(prefixes), [prefixes]);
  const total = useMemo(
    () =>
      sections.reduce(
        (sum, section) => sum + section.groups.reduce((n, g) => n + g.modules.length, 0),
        0,
      ),
    [sections],
  );

  if (total === 0) {
    return (
      <EmptyState
        icon={<Boxes className="h-6 w-6" />}
        title="本域还没有真实页面"
        description="这一页下面还没有已经交付的模块页面，所以这里不列任何东西 —— 与其画一张假的模块清单，不如说清楚「还没有」。"
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="text-copy-13 text-muted-foreground">
        本域已交付 <strong className="text-foreground">{total}</strong> 个模块页面，
        下列每一项都是可以直接打开的真实工作区 —— 业务数据在各自的模块页里。
      </div>

      {sections.map((section) => (
        <SectionCard
          key={section.id}
          title={`${section.id} ${section.name}`}
          description="按功能蓝图的 L2 能力域分组"
        >
          <div className="space-y-4">
            {section.groups.map((group) => (
              <div key={group.id} className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">
                  {group.id} {group.name}
                </div>
                <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3">
                  {group.modules.map((entry) => (
                    <li key={entry.href}>
                      <Link
                        href={entry.href}
                        className="border-border bg-background hover:border-brand/40 flex h-full items-start gap-2.5 rounded-lg border p-3 transition-colors"
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge tone="neutral">{entry.moduleId}</Badge>
                            <span className="text-copy-13 text-foreground font-medium">
                              {entry.name}
                            </span>
                            {entry.variant && (
                              <span className="text-copy-12 text-muted-foreground">
                                （{entry.variant}）
                              </span>
                            )}
                          </div>
                          <div className="text-copy-12 text-muted-foreground mt-1 break-all">
                            {entry.href}
                          </div>
                        </div>
                        <ArrowRight className="text-muted-foreground mt-0.5 h-3.5 w-3.5 shrink-0" />
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </SectionCard>
      ))}
    </div>
  );
}
