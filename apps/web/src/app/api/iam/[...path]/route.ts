import { NextRequest, NextResponse } from 'next/server';
import { proxy } from '../../../../lib/api-proxy';
export const dynamic = 'force-dynamic';
async function identityProxy(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  if (!['auth', 'identity'].includes((await context.params).path[0] ?? ''))
    return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  return proxy(request, context);
}
export const GET = identityProxy;
export const POST = identityProxy;
export const PUT = identityProxy;
export const DELETE = identityProxy;
