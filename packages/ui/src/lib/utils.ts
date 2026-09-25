import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * cn — merge Tailwind classes with conflict resolution
 * 用法: cn('p-4', condition && 'bg-red-500', className)
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * num — 等宽数字 class
 * 用于 KPI 数字、价格、订单号等场景
 */
export const numClass = 'num tabular-nums';

export function num(value: number | string, decimals?: number): string {
  const n = typeof value === 'string' ? parseFloat(value) : value;
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('en-US', {
    minimumFractionDigits: decimals ?? 0,
    maximumFractionDigits: decimals ?? 2,
  });
}

export function pct(value: number, decimals = 1): string {
  return `${value.toFixed(decimals)}%`;
}