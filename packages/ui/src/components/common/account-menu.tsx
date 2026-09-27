'use client';

import * as React from 'react';
import { ChevronDown, UserRound, Building2, X } from 'lucide-react';
import { Popover, PopoverTrigger, PopoverContent } from '../ui/popover';
import { Sheet, SheetContent, SheetTitle, SheetClose } from '../ui/sheet';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { FormField } from './form-field';
import { Segmented } from './segmented';

type PreviewRole = 'member' | 'super-admin';
type Profile = { name: string; email: string };
type TenantInfo = { name: string; contact: string };

/** Frontend-only preview. No credentials, tenant identifiers or real authorization are created. */
export function AccountMenu() {
  const [open, setOpen] = React.useState(false);
  const [editor, setEditor] = React.useState<'profile' | 'tenant' | null>(null);
  const [role, setRole] = React.useState<PreviewRole>('member');
  const [profile, setProfile] = React.useState<Profile>({ name: '体验用户', email: '' });
  const [tenant, setTenant] = React.useState<TenantInfo>({ name: '', contact: '' });
  const [draftName, setDraftName] = React.useState('');
  const [draftEmail, setDraftEmail] = React.useState('');
  const [error, setError] = React.useState('');
  const [notice, setNotice] = React.useState('');
  const trigger = React.useRef<HTMLButtonElement>(null);
  const nameInput = React.useRef<HTMLInputElement>(null);
  const canEditTenant = role === 'super-admin';
  const edit = (kind: 'profile' | 'tenant') => {
    if (kind === 'tenant' && !canEditTenant) return;
    setDraftName(kind === 'profile' ? profile.name : tenant.name);
    setDraftEmail(kind === 'profile' ? profile.email : tenant.contact);
    setError('');
    setNotice('');
    setOpen(false);
    setEditor(kind);
  };
  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            ref={trigger}
            type="button"
            aria-label={`账户：${profile.name}`}
            className="hover:bg-muted flex h-9 min-w-0 shrink-0 items-center gap-2 rounded-md px-2"
          >
            <span className="bg-brand-soft text-brand hidden h-7 w-7 shrink-0 items-center justify-center rounded-full lg:flex">
              <UserRound className="h-4 w-4" />
            </span>
            <span className="text-copy-13 max-w-20 truncate">{profile.name}</span>
            <ChevronDown className="hidden h-3.5 w-3.5 sm:block" />
          </button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          aria-label="账户信息"
          className="w-80 max-w-[calc(100vw-2rem)] p-4"
          onCloseAutoFocus={(event) => {
            if (editor) event.preventDefault();
          }}
        >
          <p className="text-label-14 font-semibold">{profile.name}</p>
          <p className="text-copy-12 text-muted-foreground mt-1 break-all">{profile.email}</p>
          <p className="text-copy-12 text-muted-foreground mt-1">交互原型 · 未接入真实账户</p>
          <section aria-label="租户信息" className="border-border my-4 rounded-md border p-3">
            <h2 className="text-label-12 flex items-center gap-2">
              <Building2 className="h-3.5 w-3.5" />
              租户信息
            </h2>
            <p className="text-copy-13 mt-2 break-words">{tenant.name || '尚未连接租户'}</p>
            <p className="text-copy-12 text-muted-foreground mt-1 break-all">
              {tenant.contact || '未设置联系邮箱'}
            </p>
            <p className="text-copy-12 text-muted-foreground mt-2">
              {canEditTenant ? '超级管理员 · 可编辑租户信息' : '普通用户 · 租户信息只读'}
            </p>
          </section>
          <div className="flex flex-col gap-2">
            <Button variant="outline" onClick={() => edit('profile')}>
              修改个人信息
            </Button>
            {canEditTenant && (
              <Button variant="outline" onClick={() => edit('tenant')}>
                修改租户信息
              </Button>
            )}
          </div>
          <div className="border-border mt-4 border-t pt-3">
            <p className="text-copy-12 text-muted-foreground mb-2">权限预览（仅改变原型展示）</p>
            <Segmented
              aria-label="预览角色"
              value={role}
              onChange={setRole}
              options={[
                { value: 'member', label: '普通用户' },
                { value: 'super-admin', label: '超级管理员' },
              ]}
            />
          </div>
        </PopoverContent>
      </Popover>
      <Sheet
        open={editor !== null}
        onOpenChange={(value) => {
          if (!value) setEditor(null);
        }}
      >
        <SheetContent
          aria-describedby="account-preview-note"
          className="flex w-full max-w-full flex-col p-4 sm:max-w-md"
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            trigger.current?.focus();
          }}
        >
          <div className="flex items-center justify-between">
            <SheetTitle>{editor === 'tenant' ? '修改租户信息' : '修改个人信息'}</SheetTitle>
            <SheetClose aria-label="关闭账户编辑" className="hover:bg-muted rounded p-2">
              <X className="h-4 w-4" />
            </SheetClose>
          </div>
          <p id="account-preview-note" className="text-copy-13 text-muted-foreground">
            未接入真实账户。修改仅保留在本次页面会话，刷新后恢复，不会写入后台。
          </p>
          <form
            className="min-h-0 space-y-4 overflow-y-auto"
            onSubmit={(event) => {
              event.preventDefault();
              if (editor === 'tenant' && !canEditTenant) {
                setError('当前角色不能修改租户信息。');
                return;
              }
              if (!draftName.trim()) {
                setError('请输入名称。');
                nameInput.current?.focus();
                return;
              }
              if (editor === 'tenant')
                setTenant({ name: draftName.trim(), contact: draftEmail.trim() });
              else setProfile({ name: draftName.trim(), email: draftEmail.trim() });
              setEditor(null);
              setNotice('已更新原型信息，仅本次页面会话有效。');
            }}
          >
            <FormField
              id="account-name"
              label={editor === 'tenant' ? '租户名称' : '用户姓名'}
              error={error || undefined}
            >
              <Input
                ref={nameInput}
                value={draftName}
                maxLength={80}
                onChange={(event) => {
                  setDraftName(event.target.value);
                  setError('');
                }}
              />
            </FormField>
            <FormField id="account-email" label={editor === 'tenant' ? '租户联系邮箱' : '个人邮箱'}>
              <Input
                type="email"
                value={draftEmail}
                maxLength={254}
                onChange={(event) => setDraftEmail(event.target.value)}
              />
            </FormField>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setEditor(null)}>
                取消
              </Button>
              <Button type="submit">保存原型修改</Button>
            </div>
          </form>
        </SheetContent>
      </Sheet>
      <span role="status" className="sr-only">
        {notice}
      </span>
    </>
  );
}
