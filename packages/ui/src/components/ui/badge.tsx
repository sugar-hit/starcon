import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils';

/**
 * Badge — 7 种 tone
 * tone 跟语义挂钩，不是跟颜色挂钩
 *   neutral / primary / success / warning / danger / info / brand
 */
const badgeVariants = cva(
  'inline-flex items-center rounded-full px-2 py-0.5 text-label-12 transition-colors duration-base ease-out-expo',
  {
    variants: {
      tone: {
        neutral: 'bg-gray-2 text-foreground/80',
        primary: 'bg-foreground text-background',
        success: 'bg-emerald-50 text-emerald-700',
        warning: 'bg-amber-50 text-amber-700',
        danger: 'bg-rose-50 text-rose-700',
        info: 'bg-sky-50 text-sky-700',
        brand: 'bg-brand-soft text-brand',
      },
    },
    defaultVariants: {
      tone: 'neutral',
    },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}