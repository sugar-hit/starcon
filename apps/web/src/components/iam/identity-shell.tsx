'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import Link from 'next/link';
import { AppShell, Button, Popover, PopoverTrigger, PopoverContent } from '@starcon/ui';
import type { Access, LoginResult } from '../../../../../packages/api-contracts/src/catalog';
import { api, ApiError } from '@/lib/api';
import { loginDestination } from '@/lib/identity-navigation';
import { Field, control } from '../common/fields';

type Identity = { session: Access; reload: () => Promise<void> };
const Context = createContext<Identity | null>(null);
export function useIdentity() {
  const value = useContext(Context);
  if (!value) throw new Error('Identity context required');
  return value;
}

function announceIdentityChange() {
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
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [membershipId, setMembershipId] = useState('');
  const [epoch, setEpoch] = useState(0);
  const [accountOpen, setAccountOpen] = useState(false);
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
  if (pathname === '/styleguide') return <AppShell>{children}</AppShell>;
  if (!ready)
    return (
      <p role="status" className="p-8">
        正在验证登录状态…
      </p>
    );
  if (!session)
    return (
      <main className="bg-background flex min-h-dvh items-center justify-center p-5">
        <section className="bg-card w-full max-w-md space-y-6 rounded-xl border p-8">
          <div>
            <p className="text-brand text-sm font-semibold">STARCON · 跨境电商 ERP</p>
            <h1 className="mt-3 text-2xl font-semibold">登录 Starcon</h1>
            <p className="text-muted-foreground mt-2 text-sm">
              一个账号，进入你获授权的企业工作空间。
            </p>
          </div>
          <form
            className="space-y-4"
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
                className={control}
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
                className={control}
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  setChoices([]);
                  setMembershipId('');
                }}
              />
            </Field>
            {choices.length > 1 && (
              <Field label="选择租户">
                <select
                  required
                  className={control}
                  value={membershipId}
                  onChange={(e) => setMembershipId(e.target.value)}
                >
                  <option value="">请选择企业工作空间</option>
                  {choices.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name}
                    </option>
                  ))}
                </select>
              </Field>
            )}
            {error && (
              <p role="alert" className="text-destructive text-sm">
                {error}
              </p>
            )}
            <Button className="w-full" type="submit" disabled={busy}>
              {busy ? '登录中…' : choices.length ? '进入工作空间' : '登录'}
            </Button>
          </form>
          <p className="text-muted-foreground text-xs">
            没有账号或无法登录？请联系企业管理员开通成员身份。
          </p>
        </section>
      </main>
    );
  const account = (
    <Popover open={accountOpen} onOpenChange={setAccountOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" className="max-w-32 truncate" aria-label="账户与租户">
          <span className="truncate">{session.tenantName}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-64 space-y-4">
        <p className="text-sm font-semibold">当前企业工作空间</p>
        <select
          aria-label="切换租户"
          className={control}
          disabled={busy}
          value={session.membershipId}
          onChange={async (e) => {
            if (!window.confirm('切换租户将关闭当前页面的未保存内容，确认继续？')) return;
            setBusy(true);
            setError('');
            try {
              await api('auth/switch', 'POST', { membershipId: e.target.value });
              announceIdentityChange();
              setSession(null);
              setReady(false);
              setAccountOpen(false);
              await reload();
              setEpoch((n) => n + 1);
              router.replace('/');
            } catch (e) {
              setError(e instanceof Error ? e.message : '切换失败');
            } finally {
              setReady(true);
              setBusy(false);
            }
          }}
        >
          {memberships.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </select>
        <Link href="/account" onClick={() => setAccountOpen(false)} className="block text-sm">
          我的账户
        </Link>
        <Link href="/org" onClick={() => setAccountOpen(false)} className="block text-sm">
          组织管理
        </Link>
        <Button
          variant="ghost"
          size="sm"
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
          退出
        </Button>
      </PopoverContent>
    </Popover>
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
