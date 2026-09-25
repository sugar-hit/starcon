import * as React from 'react';
import { cn } from '../../lib/utils';

/**
 * Logo — 纯文字 Logo（类 Panasonic 排版）
 * tone:
 *   light  深字，白底
 *   dark   反白，深色场景
 *   brand  浅蓝底
 */
export interface LogoProps {
  tone?: 'light' | 'dark' | 'brand';
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

const sizeMap = {
  sm: { primary: 'text-h-14', secondary: 'text-[10px]' },
  md: { primary: 'text-h-16', secondary: 'text-label-12' },
  lg: { primary: 'text-h-20', secondary: 'text-label-13' },
};

export function Logo({ tone = 'light', size = 'md', className }: LogoProps) {
  const sizes = sizeMap[size];

  const toneClass =
    tone === 'dark'
      ? 'text-white [&_.secondary]:text-white/60'
      : tone === 'brand'
        ? 'text-brand [&_.secondary]:text-brand/70 bg-brand-soft'
        : 'text-foreground [&_.secondary]:text-muted-foreground';

  return (
    <div className={cn('inline-flex flex-col leading-none', toneClass, className)}>
      <span
        className={cn(
          sizes.primary,
          'font-black tracking-[0.04em] uppercase select-none'
        )}
      >
        STARCON
      </span>
      <span
        className={cn(
          sizes.secondary,
          'secondary mt-1 font-medium tracking-[0.05em] select-none'
        )}
      >
        跨境电商 ERP
      </span>
    </div>
  );
}