import * as React from 'react';
import { ListChecks } from 'lucide-react';
import { cn } from '../../lib/utils';
import type { DomainFeature } from '../../lib/domain-spec';

/**
 * FeatureList — 展示某个 domain 的功能边界清单。
 *
 * 🔴 **这里刻意不显示交付状态**。
 *
 * <p>以前每条功能后面挂着一枚「待实施 / 实施中 / 已交付」的徽章，
 * 而那份状态是**手维护**的 —— 它在仓库里漂移过一次：台账写着 465/465 已交付，
 * 页面上却还挂着「待实施」。
 *
 * <p>交付状态是事实，事实要从真实存在的东西推出来，不能靠手写：
 * 域首页的模块目录由 `tools/codegen/build-domain-modules.mjs` 扫真实页面生成，
 * 功能台账在 `docs/product/capability-blueprint/module-progress.md`。
 * 这一层只负责说清「这个域应该具备哪些能力、每项解决什么问题」。
 */
export interface FeatureListProps {
  features: DomainFeature[];
  className?: string;
}

export function FeatureList({ features, className }: FeatureListProps) {
  if (!features.length) {
    return <div className="text-copy-13 text-muted-foreground py-4 text-center">暂未定义功能</div>;
  }
  return (
    <ul className={cn('grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-3', className)}>
      {features.map((f) => (
        <li key={f.name} className="border-border bg-background rounded-lg border p-3.5">
          <div className="flex items-start gap-2.5">
            <div className="bg-muted text-muted-foreground mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md">
              <ListChecks className="h-3.5 w-3.5" />
            </div>
            <div className="min-w-0">
              <div className="text-copy-14 text-foreground font-medium">{f.name}</div>
              <div className="text-copy-13 text-muted-foreground mt-1 leading-relaxed">
                {f.description}
              </div>
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}
