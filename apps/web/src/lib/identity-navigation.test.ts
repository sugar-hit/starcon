import { expect, it } from 'vitest';
import { loginDestination } from './identity-navigation';
it('登录回跳仅允许本应用路径，拒绝协议相对地址、控制字符与登录循环', () => {
  for (const value of [
    null,
    'https://attacker.example',
    '//attacker.example',
    '/\\attacker.example',
    '/\n/attacker.example',
    '/login?next=/orders',
    '/../login',
  ])
    expect(loginDestination(value)).toBe('/');
  expect(loginDestination('/org/members?status=ACTIVE')).toBe('/org/members?status=ACTIVE');
});
