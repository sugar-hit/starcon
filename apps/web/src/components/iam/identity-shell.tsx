'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  AppShell,
  Avatar,
  Badge,
  Button,
  Modal,
  ModalClose,
  ModalCloseButton,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@starcon/ui';
import {
  Building2,
  Check,
  ChevronDown,
  KeyRound,
  LogOut,
  Settings2,
  ShieldCheck,
  Upload,
  UserRound,
} from 'lucide-react';
import type { Access, LoginResult } from '../../../../../packages/api-contracts/src/index';
import { api, ApiError, blockWorkspaceRequests } from '@/lib/api';
import { loginDestination } from '@/lib/identity-navigation';
import { Field, control } from '../common/fields';
import { LoginScene } from './login-scene';

type Identity = { session: Access; reload: () => Promise<void> };
const Context = createContext<Identity | null>(null);
export function useIdentity() {
  const value = useContext(Context);
  if (!value) throw new Error('Identity context required');
  return value;
}

/**
 * 切换租户后必须让整个工作空间重新认身份：清掉按租户缓存的工作区页签，
 * 并广播一次身份变更。导出给 17.1.1.1 租户列表复用，避免两处各写一份而其中一份漏掉。
 */
export function announceIdentityChange() {
  try {
    window.localStorage.removeItem('starcon.workspace.tabs.v2');
    window.localStorage.setItem('starcon.identity.changed', crypto.randomUUID());
  } catch {
    /* Storage can be disabled; same-tab state still resets. */
  }
}

export function IdentityShell({ children }: { children: React.ReactNode }) {
  const intentionalLogout = useRef(false);
  const pathname = usePathname();
  const router = useRouter();
  const [session, setSession] = useState<Access | null>(null);
  const [memberships, setMemberships] = useState<LoginResult['memberships']>([]);
  const [choices, setChoices] = useState<LoginResult['memberships']>([]);
  const [confirmingWorkspace, setConfirmingWorkspace] = useState(false);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [membershipId, setMembershipId] = useState('');
  const [epoch, setEpoch] = useState(0);
  const [accountOpen, setAccountOpen] = useState(false);
  const [tenantOpen, setTenantOpen] = useState(false);
  const [pendingMembershipId, setPendingMembershipId] = useState<string | null>(null);
  const [avatar, setAvatar] = useState<string | undefined>();
  const avatarInput = useRef<HTMLInputElement>(null);
  const reload = useCallback(async () => {
    const access = await api<Access>('auth/session');
    const available = await api<LoginResult['memberships']>('auth/memberships');
    setSession(access);
    setMemberships(available);
  }, []);
  useEffect(() => {
    void reload()
      .catch((e: unknown) => {
        if (!(e instanceof ApiError && e.status === 401))
          setError(e instanceof Error ? e.message : '连接失败');
      })
      .finally(() => setReady(true));
    const expire = () => {
      blockWorkspaceRequests(false);
      setConfirmingWorkspace(false);
      setSession(null);
      setMemberships([]);
      setEpoch((n) => n + 1);
    };
    window.addEventListener('identity-expired', expire);
    const synchronize = (event: StorageEvent) => {
      if (event.key === 'starcon.identity.changed') window.location.reload();
    };
    window.addEventListener('storage', synchronize);
    return () => {
      window.removeEventListener('identity-expired', expire);
      window.removeEventListener('storage', synchronize);
    };
  }, [reload]);
  useEffect(() => {
    if (!session) return;
    const readAvatar = () => {
      try {
        setAvatar(window.localStorage.getItem(`starcon.avatar.${session.userId}`) ?? undefined);
      } catch {
        setAvatar(undefined);
      }
    };
    const onStorage = (event: StorageEvent) => {
      if (event.key === `starcon.avatar.${session.userId}`) readAvatar();
    };
    readAvatar();
    window.addEventListener('starcon-avatar-changed', readAvatar);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('starcon-avatar-changed', readAvatar);
      window.removeEventListener('storage', onStorage);
    };
  }, [session]);
  useEffect(() => {
    if (
      ready &&
      !session &&
      !error &&
      !intentionalLogout.current &&
      pathname !== '/login' &&
      pathname !== '/styleguide'
    ) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [ready, session, error, pathname, router]);
  const confirmWorkspace = async () => {
    setBusy(true);
    setError('');
    try {
      await reload();
      setEpoch((n) => n + 1);
      setConfirmingWorkspace(false);
      blockWorkspaceRequests(false);
      router.replace('/');
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setSession(null);
        setConfirmingWorkspace(false);
        blockWorkspaceRequests(false);
      } else {
        setError('无法确认当前工作空间，请重试。业务操作已暂停。');
      }
    } finally {
      setBusy(false);
    }
  };
  const switchMembership = async (nextMembershipId: string) => {
    // Close the request gate before React renders so old async callbacks cannot submit writes.
    blockWorkspaceRequests(true);
    setConfirmingWorkspace(true);
    setBusy(true);
    setError('');
    setAccountOpen(false);
    setPendingMembershipId(null);
    try {
      await api('auth/switch', 'POST', { membershipId: nextMembershipId });
    } catch (e) {
      if (e instanceof ApiError && [400, 401, 403, 404, 409, 422, 429].includes(e.status)) {
        blockWorkspaceRequests(false);
        setConfirmingWorkspace(false);
        setError(e.message);
        setBusy(false);
        return;
      }
      // Transport and server errors cannot tell us whether the mutation committed.
    }
    announceIdentityChange();
    await confirmWorkspace();
  };
  if (confirmingWorkspace)
    return (
      <main className="bg-background flex h-dvh items-center justify-center">
        <div className="max-w-md space-y-4 p-6" aria-busy={busy}>
          <p role="status">正在确认工作空间</p>
          {error && (
            <p role="alert" className="text-destructive text-sm">
              {error}
            </p>
          )}
          <Button disabled={busy} onClick={() => void confirmWorkspace()}>
            {busy ? '正在核验…' : '重试核验'}
          </Button>
        </div>
      </main>
    );
  if (pathname === '/styleguide') return <AppShell>{children}</AppShell>;
  if (!ready)
    return (
      <main className="bg-background flex h-dvh items-center justify-center" aria-busy="true">
        <div className="flex flex-col items-center gap-4" role="status">
          <span className="border-brand/20 border-t-brand h-8 w-8 animate-spin rounded-full border-2" />
          <p className="text-muted-foreground text-sm">正在恢复工作空间…</p>
        </div>
      </main>
    );
  if (!session)
    return (
      <LoginScene>
        <div className="space-y-7">
          <div>
            <p className="text-muted-foreground mb-3 text-xs tracking-[0.12em]">
              你的业务，从这里启程
            </p>
            <h1 className="text-2xl font-semibold tracking-tight">登录 Starcon</h1>
            <p className="text-muted-foreground mt-2 text-sm">欢迎回来，请登录你的企业工作空间。</p>
          </div>
          <form
            className="space-y-5"
            onSubmit={async (event) => {
              event.preventDefault();
              setBusy(true);
              setError('');
              try {
                const result = await api<LoginResult>('auth/login', 'POST', {
                  username,
                  password,
                  membershipId: membershipId || null,
                });
                if (result.memberships.length > 1 && !membershipId) {
                  setChoices(result.memberships);
                } else {
                  intentionalLogout.current = false;
                  setAccountOpen(false);
                  announceIdentityChange();
                  setPassword('');
                  setChoices([]);
                  setMembershipId('');
                  await reload();
                  const next = new URLSearchParams(window.location.search).get('next');
                  router.replace(loginDestination(next));
                }
              } catch (e) {
                setError(e instanceof Error ? e.message : '登录失败');
              } finally {
                setBusy(false);
              }
            }}
          >
            <Field label="账号">
              <input
                required
                autoComplete="username"
                placeholder="输入你的账号"
                className={`${control} h-11 bg-white`}
                value={username}
                onChange={(e) => {
                  setUsername(e.target.value);
                  setChoices([]);
                  setMembershipId('');
                }}
              />
            </Field>
            <Field label="密码">
              <input
                required
                type="password"
                autoComplete="current-password"
                placeholder="输入登录密码"
                className={`${control} h-11 bg-white`}
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  setChoices([]);
                  setMembershipId('');
                }}
              />
            </Field>
            {choices.length > 1 && (
              <fieldset className="space-y-2">
                <legend className="text-copy-13 mb-2 font-medium">选择企业工作空间</legend>
                {choices.map((choice) => (
                  <button
                    key={choice.id}
                    type="button"
                    role="radio"
                    aria-checked={membershipId === choice.id}
                    onClick={() => setMembershipId(choice.id)}
                    className={`border-border flex w-full items-center gap-3 rounded-md border p-3 text-left text-sm ${
                      membershipId === choice.id ? 'border-brand bg-brand-soft' : 'hover:bg-muted'
                    }`}
                  >
                    <Building2 className="text-muted-foreground h-4 w-4" />
                    <span className="min-w-0 flex-1 truncate">{choice.name}</span>
                    {membershipId === choice.id && <Check className="text-brand h-4 w-4" />}
                  </button>
                ))}
              </fieldset>
            )}
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button
              className="h-11 w-full"
              type="submit"
              disabled={busy || (choices.length > 1 && !membershipId)}
            >
              {busy ? '登录中…' : choices.length ? '进入工作空间' : '登录'}
            </Button>
          </form>
          <p className="text-muted-foreground border-t pt-5 text-xs leading-6">
            没有账号或无法登录？
            <br />
            请联系企业管理员开通成员身份。
          </p>
        </div>
      </LoginScene>
    );
  const owner = session.grants.some(
    (grant) => grant.roleCode === 'OWNER' && grant.scopeMode === 'ALL',
  );
  const account = (
    <>
      <Popover open={accountOpen} onOpenChange={setAccountOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            className="hover:bg-muted focus-visible:ring-brand flex h-9 min-w-0 max-w-48 items-center gap-2 rounded-md px-1.5 text-left transition-colors focus-visible:outline-none focus-visible:ring-2"
            aria-label="账户与租户"
          >
            <Avatar
              size="sm"
              {...(avatar ? { src: avatar } : {})}
              name={session.tenantName}
              className="bg-brand-soft text-brand ring-0"
            />
            <span className="text-copy-13 hidden max-w-28 truncate font-medium sm:block">
              {session.tenantName}
            </span>
            <ChevronDown className="text-muted-foreground hidden h-3.5 w-3.5 shrink-0 sm:block" />
          </button>
        </PopoverTrigger>
        <PopoverContent
          align="end"
          sideOffset={8}
          className="w-96 max-w-[calc(100vw-1rem)] overflow-hidden rounded-md p-0"
        >
          <div className="border-border flex items-start gap-3 border-b p-4">
            <div className="group relative">
              <Avatar size="lg" {...(avatar ? { src: avatar } : {})} name={session.tenantName} />
              <button
                type="button"
                aria-label="上传或修改头像"
                onClick={() => avatarInput.current?.click()}
                className="bg-foreground/75 absolute inset-0 flex items-center justify-center rounded-full text-white opacity-0 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100"
              >
                <Upload className="h-4 w-4" />
              </button>
              <input
                ref={avatarInput}
                type="file"
                accept="image/png,image/jpeg,image/webp"
                className="sr-only"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (!file) return;
                  if (file.size > 2_000_000) {
                    setError('头像文件不能超过 2 MB');
                    return;
                  }
                  const reader = new FileReader();
                  reader.onload = () => {
                    const value = typeof reader.result === 'string' ? reader.result : undefined;
                    if (!value) return;
                    try {
                      window.localStorage.setItem(`starcon.avatar.${session.userId}`, value);
                      setAvatar(value);
                      window.dispatchEvent(new Event('starcon-avatar-changed'));
                      setError('');
                    } catch {
                      setError('头像保存失败，请选择更小的图片');
                    }
                  };
                  reader.readAsDataURL(file);
                }}
              />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <p className="text-copy-14 font-semibold">我的账户</p>
                <Badge tone="success">已登录</Badge>
              </div>
              <p className="text-muted-foreground mt-1 truncate text-xs">{session.tenantName}</p>
              <button
                type="button"
                onClick={() => avatarInput.current?.click()}
                className="text-brand mt-1.5 text-xs font-medium"
              >
                {avatar ? '修改头像' : '上传头像'}
              </button>
            </div>
          </div>
          <div className="space-y-2 p-3">
            <dl className="grid grid-cols-2 gap-2">
              <div className="bg-muted/60 rounded-md p-3">
                <dt className="text-muted-foreground flex items-center gap-1.5 text-xs">
                  <UserRound className="h-3.5 w-3.5" /> 当前身份
                </dt>
                <dd className="mt-1.5 text-sm font-semibold">
                  {owner ? '租户所有者' : '租户成员'}
                </dd>
              </div>
              <div className="bg-muted/60 rounded-md p-3">
                <dt className="text-muted-foreground flex items-center gap-1.5 text-xs">
                  <KeyRound className="h-3.5 w-3.5" /> 权限状态
                </dt>
                <dd className="mt-1.5 text-sm font-semibold">
                  {session.grants.length} 个角色 · {session.permissions.length} 项权限
                </dd>
              </div>
            </dl>
            <div className="border-border rounded-md border p-3">
              <p className="text-muted-foreground mb-2 flex items-center gap-2 text-xs font-medium">
                <Building2 className="h-3.5 w-3.5" /> 当前企业工作空间
              </p>
              <Popover open={tenantOpen} onOpenChange={setTenantOpen}>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    aria-label="切换租户"
                    aria-expanded={tenantOpen}
                    disabled={busy}
                    className="border-control-border bg-card focus-visible:ring-brand flex h-10 w-full items-center gap-2 rounded-md border px-3 text-left text-sm focus-visible:outline-none focus-visible:ring-2"
                  >
                    <span className="min-w-0 flex-1 truncate font-medium">
                      {session.tenantName}
                    </span>
                    <ChevronDown className="text-muted-foreground h-4 w-4" />
                  </button>
                </PopoverTrigger>
                <PopoverContent
                  align="start"
                  className="w-[var(--radix-popover-trigger-width)] p-1"
                >
                  <div role="listbox" aria-label="企业工作空间" className="space-y-0.5">
                    {memberships.map((membership) => {
                      const selected = membership.id === session.membershipId;
                      return (
                        <button
                          key={membership.id}
                          type="button"
                          role="option"
                          aria-selected={selected}
                          className={`flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm ${
                            selected ? 'bg-brand-soft text-brand' : 'hover:bg-muted'
                          }`}
                          onClick={() => {
                            setTenantOpen(false);
                            if (!selected) {
                              setAccountOpen(false);
                              setPendingMembershipId(membership.id);
                            }
                          }}
                        >
                          <span className="min-w-0 flex-1 truncate">{membership.name}</span>
                          {selected && <Check className="h-4 w-4" />}
                        </button>
                      );
                    })}
                  </div>
                </PopoverContent>
              </Popover>
            </div>
            <p className="text-muted-foreground px-1 text-[11px]">
              会话受租户边界保护 · 成员身份 {session.membershipId.slice(0, 8)}
            </p>
            <nav aria-label="账户设置" className="flex flex-col">
              <Link
                href="/account"
                onClick={() => setAccountOpen(false)}
                className="hover:bg-muted flex items-center gap-3 rounded-md px-3 py-2 text-sm"
              >
                <Settings2 className="text-muted-foreground h-4 w-4" /> 个人资料与头像
              </Link>
              <Link
                href="/org"
                onClick={() => setAccountOpen(false)}
                className="hover:bg-muted flex items-center gap-3 rounded-md px-3 py-2 text-sm"
              >
                <ShieldCheck className="text-muted-foreground h-4 w-4" /> 组织与权限
              </Link>
            </nav>
          </div>
          <div className="border-border border-t p-2">
            <Button
              variant="ghost"
              size="sm"
              aria-label="退出"
              className="text-muted-foreground hover:text-destructive w-full justify-start gap-3"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setError('');
                try {
                  await api('auth/logout', 'POST');
                  intentionalLogout.current = true;
                  setAccountOpen(false);
                  announceIdentityChange();
                  setSession(null);
                  setMemberships([]);
                  setPassword('');
                  setEpoch((n) => n + 1);
                  router.replace('/login');
                } catch (e) {
                  setError(e instanceof Error ? e.message : '退出失败');
                } finally {
                  setBusy(false);
                }
              }}
            >
              <LogOut className="h-4 w-4" /> 退出登录
            </Button>
          </div>
        </PopoverContent>
      </Popover>
      <Modal
        open={pendingMembershipId !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setPendingMembershipId(null);
        }}
      >
        <ModalContent>
          <ModalHeader>
            <ModalTitle>切换企业工作空间</ModalTitle>
            <ModalDescription>
              切换后将关闭当前页面的未保存内容，并重新加载目标工作空间。
            </ModalDescription>
          </ModalHeader>
          <ModalCloseButton disabled={busy} />
          <ModalFooter>
            <ModalClose asChild>
              <Button variant="outline" disabled={busy}>
                取消
              </Button>
            </ModalClose>
            <Button
              disabled={busy || !pendingMembershipId}
              onClick={() => pendingMembershipId && void switchMembership(pendingMembershipId)}
            >
              {busy ? '正在切换…' : '确认切换'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
  return (
    <Context.Provider value={{ session, reload }}>
      <AppShell key={`${session.userId}:${session.tenantId}:${epoch}`} account={account}>
        {error && (
          <p role="alert" className="text-destructive">
            {error}
          </p>
        )}
        {pathname === '/login' ? (
          <div className="p-6">
            <p>你已登录 {session.tenantName}</p>
            <Link href="/">进入工作台</Link>
          </div>
        ) : (
          children
        )}
      </AppShell>
    </Context.Provider>
  );
}
