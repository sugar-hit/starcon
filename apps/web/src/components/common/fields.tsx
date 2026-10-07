'use client';
import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react';
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalTitle,
  ModalDescription,
  ModalCloseButton,
} from '@starcon/ui';
export const control =
  'w-full min-w-0 rounded-md border border-control-border bg-card px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:bg-muted';
export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  const id = useId();
  const content =
    isValidElement(children) && typeof children.type === 'string'
      ? cloneElement(children as ReactElement<{ id?: string; 'aria-label'?: string }>, {
          id,
          'aria-label': label,
        })
      : children;
  return (
    <div className="space-y-1.5 text-sm">
      <label htmlFor={id} className="block font-medium">
        {label}
      </label>
      {content}
      {hint && <span className="text-muted-foreground block text-xs">{hint}</span>}
    </div>
  );
}
export function Editor({
  title,
  description,
  close,
  children,
}: {
  title: string;
  description: string;
  close: () => void;
  children: ReactNode;
}) {
  return (
    <Modal
      open
      onOpenChange={(open) => {
        if (!open) close();
      }}
    >
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>{title}</ModalTitle>
          <ModalDescription>{description}</ModalDescription>
        </ModalHeader>
        <ModalCloseButton />
        {children}
      </ModalContent>
    </Modal>
  );
}
/**
 * SKU 与资料记录沿用四值域；SPU 自 3.1.1.3 起走五态生命周期，两者共用同一张标签表。
 */
export const statuses: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_LISTING: '待上架',
  ON_SALE: '在售',
  SUSPENDED: '停售',
  DISCONTINUED: '停产',
  ACTIVE: '启用',
  INACTIVE: '停用',
  ARCHIVED: '已归档',
  REVIEW: '待评审',
  APPROVED: '已确认',
};
export const modes: Record<string, string> = {
  COMMON_LOCKED: '公共固定',
  COMMON_DEFAULT: '公共默认 · 可覆写',
  SKU_ONLY: 'SKU 独立',
};
export const types: Record<string, string> = {
  TEXT: '文本',
  DECIMAL: '数值',
  BOOLEAN: '是 / 否',
  DATE: '日期',
  OPTION: '选项',
};
