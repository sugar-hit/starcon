import * as React from 'react';
import Image from 'next/image';
import { cn } from '../../lib/utils';

/**
 * Logo — image mark + styled wordmark + subtitle
 *
 * 设计: docs/design/visual-spec.md §9 (Logo 品牌源与设计理念)
 * 真实 mark: apps/web/public/logo-mark.svg / logo-mark-dark.svg / favicon.svg
 *
 * mark 设计（品牌源 B1 精修方案）:
 *   - 4 个圆点 = 4 业务系统(开发/运营/物流/仓储)
 *   - 中心 4 角星 = STAR 概念 + 业务汇聚
 *   - 单色黑主用 / 品牌蓝版(数字化场景)
 *   - 详见 docs/design/brand-source-b1-refined.png
 *
 * 字标:
 *   - 主: STARCON(粗体大写)
 *   - 副: Commerce OS(细体 Title Case)
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
  sm: {
    box: 'h-8 w-8',
    wordmark: 'text-[14px]',
    subtitle: 'text-[10px]',
    gap: 'gap-3',
    markPx: 32,
  },
  md: {
    box: 'h-10 w-10',
    wordmark: 'text-[18px]',
    subtitle: 'text-[12px]',
    gap: 'gap-3.5',
    markPx: 40,
  },
  lg: {
    box: 'h-12 w-12',
    wordmark: 'text-[22px]',
    subtitle: 'text-[14px]',
    gap: 'gap-4',
    markPx: 48,
  },
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
  const markSrc = {
    light: '/logo-mark.svg',
    dark: '/logo-mark-dark.svg',
    brand: '/logo-mark-brand.svg',
  }[tone];

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
    <div className={cn('inline-flex items-center', sizes.gap, className)}>
      <Image
        src={markSrc}
        alt=""
        width={sizes.markPx}
        height={sizes.markPx}
        className={cn(sizes.box, 'shrink-0')}
        priority
      />
      <div className={cn('flex flex-col whitespace-nowrap', textColorClass(tone))}>
        <span
          className={cn(
            sizes.wordmark,
            'select-none font-bold uppercase leading-none tracking-[0.01em]',
          )}
        >
          STARCON
        </span>
        <span
          className={cn(
            sizes.subtitle,
            'mt-1 select-none font-normal leading-none tracking-normal',
            subtitleColorClass(tone),
          )}
        >
          Commerce OS
        </span>
      </div>
    </div>
  );
}
