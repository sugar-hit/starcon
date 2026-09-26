'use client';

import * as React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { ArrowUpRight } from 'lucide-react';
import { cn } from '../../lib/utils';
import { resolveSystemFromPath } from '../../lib/nav-config';
import { useWorkspaceTabs } from '../../lib/workspace-tabs';

/**
 * ExternalLink — 跨模块链接 (有 ⎘ 图标 + "应用内新 tab"提示)
 *
 * 用途:
 *   - 跨 domain 的关键跳转(订单 → SKU 详情 / 客户档案)
 *   - 普通 click:加 tab 并切换 (与 NavItemLink 行为一致)
 *   - ⌘+click / 中键:加 tab 但不切换 (应用内多 tab)
 *   - ⎘ 图标 + title 提示用户这是"跨模块"链接
 *
 * 退路:如果 WorkspaceTabsProvider 不在祖先 (例如 standalone 预览),则退回原生 Link
 *
 * hover 反馈:跟周围 Link 一致,不叠 4 件套
 */

export interface ExternalLinkProps extends Omit<React.ComponentProps<typeof Link>, 'href' | 'onClick'> {
  href: string;
  /** 是否显示 ⎘ 图标,默认 true */
  showIcon?: boolean;
  /** 自定义 hint 文案 */
  hint?: string;
  /** 仅在 AppShell 内生效;强制使用浏览器原生 Link(不接 tab store) */
  disableWorkspaceTabs?: boolean;
}

function useWorkspaceTabsSafe() {
  try {
    return useWorkspaceTabs();
  } catch {
    return null;
  }
}

export const ExternalLink = React.forwardRef<HTMLAnchorElement, ExternalLinkProps>(
  ({ className, children, showIcon = true, hint, disableWorkspaceTabs, href, ...props }, ref) => {
    const router = useRouter();
    const pathname = usePathname();
    const store = useWorkspaceTabsSafe();
    const targetHref = href;

    return (
      <Link
        ref={ref}
        href={targetHref}
        onClick={(e) => {
          if (disableWorkspaceTabs || !store) return;
          // 中键 / 修饰键 — 让浏览器走原生新窗口(用户明确意图)
          if (e.button === 1 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) {
            return;
          }
          e.preventDefault();
          const system = resolveSystemFromPath(pathname);
          // 从 nav-config 找 label — 简化:用 path 最后一段
          const label = targetHref.split('/').filter(Boolean).pop() || targetHref;
          store.openTab({
            id: targetHref,
            href: targetHref,
            label: decodeURIComponent(label),
            systemId: system.id,
          });
          router.push(targetHref);
        }}
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