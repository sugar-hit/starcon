import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * SectionCard — 页面级分区卡
 * 头部 border-b 分隔（不要换成阴影），extra 槽永远放右上角
 */
export interface SectionCardProps extends React.HTMLAttributes<HTMLDivElement> {
  title?: React.ReactNode;
  description?: React.ReactNode;
  extra?: React.ReactNode;
  bodyClassName?: string;
}

export const SectionCard = React.forwardRef<HTMLDivElement, SectionCardProps>(
  ({ className, title, description, extra, bodyClassName, children, ...props }, ref) => {
    const hasHeader = title || description || extra;
    return (
      <div
        ref={ref}
        className={cn(
          'rounded-lg border border-border bg-card overflow-hidden',
          className
        )}
        {...props}
      >
        {hasHeader && (
          <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
            <div className="min-w-0 flex-1 space-y-0.5">
              {title && (
                <h3 className="text-h-16 text-foreground tracking-tight">{title}</h3>
              )}
              {description && (
                <p className="text-copy-13 text-muted-foreground">{description}</p>
              )}
            </div>
            {extra && <div className="flex shrink-0 items-center gap-2">{extra}</div>}
          </div>
        )}
        <div className={cn(bodyClassName ?? 'p-5')}>{children}</div>
      </div>
    );
  }
);
SectionCard.displayName = 'SectionCard';