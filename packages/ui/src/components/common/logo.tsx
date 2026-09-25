import * as React from 'react';
import Image from 'next/image';
import { cn } from '../../lib/utils';

/**
 * Logo — image mark + styled wordmark + subtitle
 *
 * 设计: docs/design/visual-spec.md § 3.10
 * 真实 mark: apps/web/public/logo-mark.svg / logo-mark-dark.svg / favicon.svg
 *
 * mark 设计（多层几何，避开手画字形）:
 *   - 蓝紫渐变圆角方块 + 顶部高光（玻璃感）
 *   - 内嵌六边形网络线（cross-border / global）
 *   - 罗盘指针（白色钻石，朝上）+ 中央分隔带
 *   - 4 个方位点（白色，从中心递减不透明）
 *   - 右上角金色 4 角星（识别点）
 *
 * 尺寸对齐（mark ≈ wordmark + mt + subtitle）:
 *   sm: 32 / md: 40 / lg: 48
 */
export interface LogoProps {
  tone?: 'light' | 'dark' | 'brand';
  size?: 'sm' | 'md' | 'lg';
  /** 只显示 mark，不显示文字 — 移动端 / favicon */
  markOnly?: boolean;
  className?: string;
}

const sizeMap = {
  sm: { box: 'h-8 w-8',  wordmark: 'text-h-14', subtitle: 'text-[10px]',  markPx: 32 },
  md: { box: 'h-10 w-10', wordmark: 'text-h-16', subtitle: 'text-label-12', markPx: 40 },
  lg: { box: 'h-12 w-12', wordmark: 'text-h-20', subtitle: 'text-label-13', markPx: 48 },
};

const textColorClass = (tone: NonNullable<LogoProps['tone']>) => {
  if (tone === 'dark') return 'text-white';
  if (tone === 'brand') return 'text-brand';
  return 'text-foreground';
};

const subtitleColorClass = (tone: NonNullable<LogoProps['tone']>) => {
  if (tone === 'dark') return 'text-white/60';
  if (tone === 'brand') return 'text-brand/70';
  return 'text-muted-foreground';
};

export function Logo({ tone = 'light', size = 'md', markOnly = false, className }: LogoProps) {
  const sizes = sizeMap[size];
  const markSrc = tone === 'dark' ? '/logo-mark-dark.svg' : '/logo-mark.svg';

  if (markOnly) {
    return (
      <div className={cn('inline-flex items-center', className)}>
        <Image
          src={markSrc}
          alt="starcon"
          width={sizes.markPx}
          height={sizes.markPx}
          className={sizes.box}
          priority
        />
      </div>
    );
  }

  return (
    <div className={cn('inline-flex items-center gap-2.5', className)}>
      <Image
        src={markSrc}
        alt="starcon"
        width={sizes.markPx}
        height={sizes.markPx}
        className={cn(sizes.box, 'shrink-0')}
        priority
      />
      <div className={cn('flex flex-col leading-none', textColorClass(tone))}>
        <span
          className={cn(
            sizes.wordmark,
            'font-black tracking-[0.04em] uppercase select-none'
          )}
        >
          STARCON
        </span>
        <span
          className={cn(
            sizes.subtitle,
            'mt-1 font-medium tracking-[0.05em] select-none',
            subtitleColorClass(tone)
          )}
        >
          跨境电商 AI ERP
        </span>
      </div>
    </div>
  );
}