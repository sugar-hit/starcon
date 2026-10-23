'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { Command } from 'cmdk';
import { ArrowUpRight, Search, type LucideIcon } from 'lucide-react';
import { Modal, ModalContent, ModalTitle, ModalDescription } from '../ui/modal';
import { cn } from '../../lib/utils';

/**
 * CommandPalette — Cmd+K 命令面板
 *
 * 用途:
 *   - 跨模块快速跳转(Notion / Linear / Raycast 风格)
 *   - 占位"业务对象搜索"分组,数据接入后启用
 *
 * 设计:
 *   - 用户硬规则:永远不要 mock 数据
 *   - 当前只索引 nav items + 静态系统动作
 *   - 业务对象(SKU/订单号/客户)分组预留,数据接入时填入
 *
 * 用法:
 *   <CommandPalette
 *     open={open}
 *     onOpenChange={setOpen}
 *     groups={[{
 *       heading: '功能导航',
 *       items: navItems
 *     }]}
 *   />
 */

export interface CommandPaletteItem {
  /** 命令 id */
  id: string;
  /** 显示标题 */
  label: string;
  /** 副标题/描述 */
  description?: string;
  /** 跳转 URL 或回调(回调优先) */
  href?: string;
  onSelect?: () => void;
  /** 左侧图标 */
  icon?: LucideIcon;
  /** 快捷键提示(如 ⌘⇧P) */
  shortcut?: string;
  /** 禁用状态 */
  disabled?: boolean;
  /** 禁用原因说明 */
  disabledReason?: string;
}

export interface CommandPaletteGroup {
  /** 分组标题,如"功能导航"、"系统动作" */
  heading: string;
  items: CommandPaletteItem[];
}

export interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  groups: CommandPaletteGroup[];
  /** 顶部说明,默认"搜索功能、订单或跳转到..." */
  placeholder?: string;
  /** 空结果时显示 */
  emptyMessage?: string;
  className?: string;
  /**
   * 输入内容变化回调。
   *
   * <p>1.2.1.1 全局搜索用它驱动**跨服务实时检索**：面板本身不发起任何请求，
   * 只把用户键入的关键词交给业务侧（业务侧拿到结果后再把分组塞回 `groups`）。
   */
  onQueryChange?: (query: string) => void;
  /**
   * 输入框下方的状态提示（正在检索 / 检索失败 / 没有权限…）。
   *
   * <p>空串不渲染。它存在的理由：一次全局搜索要打四个服务，
   * 没有状态行的话用户在等待期间只会看到「没有匹配的命令」。
   */
  statusText?: string;
}

export function CommandPalette({
  open,
  onOpenChange,
  groups,
  placeholder = '搜索功能或执行动作…',
  emptyMessage = '没有匹配的命令。试试其他关键词。',
  className,
  onQueryChange,
  statusText,
}: CommandPaletteProps) {
  const router = useRouter();
  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent
        className={cn('max-w-xl gap-0 overflow-hidden p-0', className)}
        // cmdk 自己管理键盘事件,关闭 Radix 默认焦点陷阱(避免吞键盘)
        onOpenAutoFocus={(e: Event) => e.preventDefault()}
      >
        <ModalTitle className="sr-only">命令面板</ModalTitle>
        <ModalDescription className="sr-only">
          按 Cmd/Ctrl + K 打开,跨模块快速跳转。
        </ModalDescription>
        <Command
          label="搜索功能名称"
          shouldFilter
          loop
          className="flex max-h-[60dvh] min-h-[280px] flex-col"
        >
          <div className="border-border flex items-center gap-2 border-b px-4 py-3">
            <Search className="text-muted-foreground h-4 w-4 shrink-0" aria-hidden="true" />
            <Command.Input
              autoFocus
              placeholder={placeholder}
              {...(onQueryChange ? { onValueChange: onQueryChange } : {})}
              className="text-copy-14 placeholder:text-muted-foreground w-full bg-transparent outline-none"
            />
            <kbd className="border-border text-label-12 text-muted-foreground hidden shrink-0 rounded border px-1.5 py-0.5 sm:inline">
              Esc
            </kbd>
          </div>
          {statusText ? (
            <p role="status" className="text-label-12 text-muted-foreground border-b px-4 py-2">
              {statusText}
            </p>
          ) : null}
          <Command.List className="max-h-[60dvh] flex-1 overflow-y-auto p-2">
            <Command.Empty className="text-copy-13 text-muted-foreground px-3 py-8 text-center">
              {emptyMessage}
            </Command.Empty>
            {groups.map((group) => (
              <Command.Group
                key={group.heading}
                heading={group.heading}
                className="text-copy-12 text-muted-foreground mb-1 [&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider"
              >
                {group.items.map((item) => {
                  const Icon = item.icon;
                  const isDisabled = item.disabled;
                  return (
                    <Command.Item
                      key={item.id}
                      value={`${item.label} ${item.description ?? ''}`}
                      {...(isDisabled ? { disabled: true } : {})}
                      onSelect={() => {
                        if (isDisabled) return;
                        item.onSelect?.();
                        if (item.href) {
                          router.push(item.href);
                        }
                        onOpenChange(false);
                      }}
                      className={cn(
                        'data-[selected=true]:bg-muted aria-selected:bg-muted flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2 text-left',
                        'data-[disabled=true]:cursor-not-allowed data-[disabled=true]:opacity-60',
                      )}
                    >
                      {Icon ? (
                        <span className="bg-background text-muted-foreground border-border flex h-7 w-7 shrink-0 items-center justify-center rounded-md border">
                          <Icon className="h-3.5 w-3.5" />
                        </span>
                      ) : (
                        <span className="w-7 shrink-0" />
                      )}
                      <span className="min-w-0 flex-1">
                        <span className="text-copy-14 text-foreground block truncate font-medium">
                          {item.label}
                        </span>
                        {item.description && (
                          <span className="text-copy-12 text-muted-foreground block truncate">
                            {isDisabled && item.disabledReason
                              ? item.disabledReason
                              : item.description}
                          </span>
                        )}
                      </span>
                      {item.shortcut ? (
                        <kbd className="border-border text-label-12 text-muted-foreground shrink-0 rounded border px-1.5 py-0.5">
                          {item.shortcut}
                        </kbd>
                      ) : item.href ? (
                        <ArrowUpRight className="text-muted-foreground h-3.5 w-3.5 shrink-0" />
                      ) : null}
                    </Command.Item>
                  );
                })}
              </Command.Group>
            ))}
          </Command.List>
        </Command>
      </ModalContent>
    </Modal>
  );
}
