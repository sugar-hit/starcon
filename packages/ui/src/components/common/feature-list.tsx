import * as React from 'react';
import { CheckCircle2, CircleDashed, Hammer, type LucideIcon } from 'lucide-react';
import { Badge } from '../ui/badge';
import { cn } from '../../lib/utils';
import type { DomainFeature, FeatureStatus } from '../../lib/domain-spec';

/**
 * FeatureList — 展示某个 domain 应具备的功能清单
 *
 * 用途:
 *   - 替代 mock 数据驱动的页面骨架
 *   - 让用户一眼看出"这里有什么功能 / 当前在做什么"
 *
 * 状态映射(用品牌色一致,不引入金红暖色):
 *   planned       → 待规划/设计  (neutral)
 *   in-progress   → 实施中        (warning)
 *   shipped       → 已交付        (success)
 */

const STATUS_LABEL: Record<FeatureStatus, string> = {
  planned: '待实施',
  'in-progress': '实施中',
  shipped: '已交付',
};

const STATUS_TONE: Record<FeatureStatus, 'neutral' | 'warning' | 'success'> = {
  planned: 'neutral',
  'in-progress': 'warning',
  shipped: 'success',
};

const STATUS_ICON: Record<FeatureStatus, LucideIcon> = {
  planned: CircleDashed,
  'in-progress': Hammer,
  shipped: CheckCircle2,
};

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
      {features.map((f) => {
        const Icon = STATUS_ICON[f.status];
        return (
          <li key={f.name} className="border-border bg-background rounded-lg border p-3.5">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2.5">
                <div className="bg-muted text-muted-foreground mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md">
                  <Icon className="h-3.5 w-3.5" />
                </div>
                <div className="min-w-0">
                  <div className="text-copy-14 text-foreground font-medium">{f.name}</div>
                  <div className="text-copy-13 text-muted-foreground mt-1 leading-relaxed">
                    {f.description}
                  </div>
                </div>
              </div>
              <Badge tone={STATUS_TONE[f.status]} className="shrink-0">
                {STATUS_LABEL[f.status]}
              </Badge>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
