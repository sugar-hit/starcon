import * as React from 'react';
import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { SectionCard } from './section-card';
import { EmptyState } from './empty-state';
import { FeatureList } from './feature-list';
import { DomainStatusBanner } from './domain-status-banner';
import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import type { DomainSpec } from '../../lib/domain-spec';

/**
 * DomainPlaceholder — domain 页面骨架统一模板
 *
 * 结构:
 *   1. DomainStatusBanner  (顶部状态)
 *   2. 核心功能 SectionCard (功能清单)
 *   3. 数据来源 SectionCard (上游)
 *   4. 数据去向 SectionCard (下游)
 *
 * 注意:
 *   - 不放 mock 数据,不放 StatCard 假数字
 *   - 功能清单来源于 domain-spec.ts(单一真相源)
 *   - 上下游关联用文字链接,方便识别数据流
 */

export interface DomainPlaceholderProps {
  spec: DomainSpec;
  className?: string;
}

export function DomainPlaceholder({ spec, className }: DomainPlaceholderProps) {
  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <DomainStatusBanner
        status={spec.pageStatus}
        note="本页只描述功能边界与上下游数据流,业务数据将在功能实施后接入。"
      />

      <SectionCard
        title="核心功能"
        description={`${spec.label}应具备的功能模块,按业务优先级排列。`}
      >
        <FeatureList features={spec.features} />
      </SectionCard>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <SectionCard title="数据来源" description="本模块依赖的上游数据">
          {spec.upstream.length > 0 ? (
            <ul className="space-y-2">
              {spec.upstream.map((name) => (
                <li key={name} className="text-copy-13 text-foreground flex items-center gap-2">
                  <span className="bg-brand h-1.5 w-1.5 shrink-0 rounded-full" />
                  {name}
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState title="无上游依赖" description="本模块为基础主数据,被其他模块依赖。" />
          )}
        </SectionCard>

        <SectionCard title="数据去向" description="本模块输出的下游模块">
          {spec.downstream.length > 0 ? (
            <ul className="space-y-2">
              {spec.downstream.map((name) => (
                <li key={name} className="text-copy-13 text-foreground flex items-center gap-2">
                  <span className="bg-muted-foreground h-1.5 w-1.5 shrink-0 rounded-full" />
                  {name}
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState title="无下游输出" description="本模块为消费侧,聚合其他模块数据。" />
          )}
        </SectionCard>
      </div>

      <div className="flex justify-end">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/styleguide">
            查看设计系统
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </Button>
      </div>
    </div>
  );
}
