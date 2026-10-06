import { afterEach, expect, it, vi } from 'vitest';
import { api, blockWorkspaceRequests } from './api';

afterEach(() => {
  blockWorkspaceRequests(false);
  vi.unstubAllGlobals();
});

it('核验期间阻止旧页面读写，允许身份核验，恢复后允许业务请求', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('null'));
  vi.stubGlobal('fetch', fetch);
  blockWorkspaceRequests(true);
  await expect(api('catalog/products', 'POST', {})).rejects.toThrow('正在确认工作空间');
  await expect(api('identity/members')).rejects.toThrow('正在确认工作空间');
  expect(fetch).not.toHaveBeenCalled();
  await api('auth/session');
  expect(fetch).toHaveBeenCalledOnce();
  blockWorkspaceRequests(false);
  fetch.mockResolvedValue(new Response('[]'));
  await expect(api('catalog/products')).resolves.toEqual([]);
});

it('空成功响应可用于切换与退出', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
  await expect(api('auth/logout', 'POST')).resolves.toBeNull();
});

it('非 JSON 的 401 也触发全局失效事件', async () => {
  const dispatchEvent = vi.fn();
  vi.stubGlobal('window', { dispatchEvent });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('unauthorized', { status: 401 })));
  await expect(api('auth/session')).rejects.toMatchObject({ status: 401 });
  expect(dispatchEvent.mock.calls[0]?.[0].type).toBe('identity-expired');
});
