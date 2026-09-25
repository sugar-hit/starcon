import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * cn — merge Tailwind classes with conflict resolution
 *
 * 用法: cn('p-4', condition && 'bg-red-500', className)
 *
 * 配置:
 *   - 我们有自定义 fontSize key 用 text- 前缀 (text-h-16, text-copy-13 等),
 *     twMerge 默认会把它们跟 text-颜色类 (text-primary, text-muted-foreground)
 *     视为同组冲突, 后者覆盖前者 → 字号丢失
 *   - 解决: 在 fontSize classGroup 里显式列出我们的自定义 key,
 *     twMerge 会把它们识别为 font-size 而不是 text-color
 *   - 同样: bg-* / border-* 跟 brand / destructive 等语义色容易冲突,
 *     需要时也可以加 group
 */
const customTwMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      // 把自定义 fontSize key 注册成 font-size group
      // (twMerge 默认不在 text-* group 里 — 但默认会按 text-color 处理)
      'font-size': [
        'text-h-72', 'text-h-56', 'text-h-48', 'text-h-40', 'text-h-32', 'text-h-24',
        'text-h-20', 'text-h-16', 'text-h-14',
        'text-copy-24', 'text-copy-20', 'text-copy-18', 'text-copy-16', 'text-copy-14',
        'text-copy-13', 'text-copy-12',
        'text-label-14', 'text-label-13', 'text-label-12',
        'text-btn-14',
      ],
    },
  },
});

export function cn(...inputs: ClassValue[]) {
  return customTwMerge(clsx(inputs));
}

/**
 * num — 等宽数字 class
 * 用于 KPI 数字、价格、订单号等场景
 */
export const numClass = 'num tabular-nums';

export function num(value: number | string, decimals?: number): string {
  const n = typeof value === 'string' ? parseFloat(value) : value;
  if (Number.isNaN(n)) return String(n);
  return n.toLocaleString('en-US', {
    minimumFractionDigits: decimals ?? 0,
    maximumFractionDigits: decimals ?? 2,
  });
}

export function pct(value: number, decimals = 1): string {
  return `${value.toFixed(decimals)}%`;
}