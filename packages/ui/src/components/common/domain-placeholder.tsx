import * as React from 'react';
import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { SectionCard } from './section-card';
import { EmptyState } from './empty-state';
import { FeatureList } from './feature-list';
import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import type { DomainSpec } from '../../lib/domain-spec';

/**
 * DomainPlaceholder — 域的**功能边界说明页**（不是业务工作区）。
 *
 * <p>🔴 它现在只剩一个使用者：AI Copilot 抽屉。
 * 七个业务域首页（定价 / CRM / 供应 / 采购 / 物流 / 仓储 / 利润）
 * 此前挂的都是这个组件 —— 上面写着「骨架就位 · 功能待实施」，
 * 而这些域的模块**早已全部交付**。那是一张会骗人的页面，
 * 现在它们改成了真实的域索引（见 `apps/web/src/components/domain-hub/`）。
 *
 * <p>结构:
 *   1. 说明块（这一页只描述边界，不展示业务数据）
 *   2. 核心功能 SectionCard（功能清单）
 *   3. 数据来源 SectionCard（上游）
 *   4. 数据去向 SectionCard（下游）
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
      <div className="border-border bg-card text-copy-13 text-muted-foreground rounded-lg border px-4 py-3">
        这一页只描述功能边界与上下游数据流，
        <strong className="text-foreground">不展示业务数据</strong>
        —— 业务数据在各自已交付的模块页里。
      </div>

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
