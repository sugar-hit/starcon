'use client';

import * as React from 'react';
import Link from 'next/link';
import { ArrowUpRight } from 'lucide-react';
import { cn } from '../../lib/utils';

/**
 * ExternalLink — 跨模块链接 (有 ⎘ 图标 + Cmd/Ctrl+点击提示)
 *
 * 用途:
 *   - 跨 domain 的关键跳转(订单 → SKU 详情 / 客户档案)
 *   - 用户按住 Cmd/Ctrl 点击 = 浏览器原生"在新窗口打开"
 *   - ⎘ 图标 + title 提示用户这个链接支持新 tab 打开
 *
 * hover 反馈:
 *   - 跟周围组件保持一致 (用户硬规则:不叠 4 件套)
 *   - 默认跟 <Link> 一致:文字色 brand / underline
 *
 * 用法:
 *   <ExternalLink href="/products/sku-123">查看商品详情</ExternalLink>
 */

export interface ExternalLinkProps extends Omit<React.ComponentProps<typeof Link>, 'href'> {
  href: string;
  /** 是否显示 ⎘ 图标,默认 true */
  showIcon?: boolean;
  /** 自定义 hint 文案 */
  hint?: string;
}

export const ExternalLink = React.forwardRef<HTMLAnchorElement, ExternalLinkProps>(
  ({ className, children, showIcon = true, hint, ...props }, ref) => {
    return (
      <Link
        ref={ref}
        className={cn(
          'text-brand inline-flex items-center gap-1 whitespace-nowrap font-medium hover:underline',
          className,
        )}
        title={hint ?? '在新窗口打开(按住 Cmd/Ctrl 点击)'}
        aria-keyshortcuts="Meta+Enter Control+Enter"
        {...props}
      >
        {children}
        {showIcon && (
          <ArrowUpRight className="text-muted-foreground h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        )}
      </Link>
    );
  },
);
ExternalLink.displayName = 'ExternalLink';