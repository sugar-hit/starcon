#!/usr/bin/env python3
"""Generate TypeScript DTOs from live Spring OpenAPI. No hand-edited contracts.
Jackson record responses include every component. Nullable fields are annotated in Java.

**输出按服务分文件**：`src/contracts/<service>.ts` 各管各的，`src/index.ts` 只做汇总。
分文件之前所有服务被合并进一个扁平文件，同名 DTO 后者覆盖前者 —— 失败方是别的模块，
而且要等下一次 codegen 才炸。现在同名有两道网：
  - 结构不同 → 写出之前就非零退出（gate()），并点名谁会凭空多出 / 谁独有字段会消失；
  - 结构完全相同 → 两份都活在自己的文件里，桶文件显式指定一份消除 export * 歧义，
    忘了指定就是 TS2308 编译错误，而不是静默覆盖。
"""
import argparse, json, sys, urllib.request, subprocess, re
from pathlib import Path

root=Path(__file__).resolve().parents[2]
# trade 是 09 订单履约的服务（V92 / 9.3.2.2 起有真实端点），
# 它此前不在清单里是因为卷 09 一条端点都没有；契约导出由
# services/trade 的 OpenApiContractTest 负责，与前四个服务同一套写法。
# crm 是 15 客户与客服的服务（V99 / 15.4.2.1 起有真实端点），端口 8086 排在 trade 之后；
# 契约导出由 services/crm 的 OpenApiContractTest 负责，同一套写法。
SERVICES=[('identity',8081),('catalog',8082),('channel',8083),('warehouse',8084),('trade',8085),('crm',8086)]
ALLOWLIST=Path(__file__).resolve().parent/'contract-collision-allowlist.json'
BACKLOG='docs/backlog/2026-10/2026-10-10-contract-flat-overwrite-and-nav-gaps.md'


def normalize_operation_ids(spec):
    """SpringDoc suffixes duplicate method names in discovery order; use stable route IDs."""
    ids = {}
    for path, item in sorted(spec.get('paths', {}).items()):
        for method, operation in item.items():
            if method not in {'get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'}:
                continue
            operation_id = method + '_' + re.sub(r'[^a-zA-Z0-9]+', '_', path).strip('_')
            if operation_id in ids:
                raise ValueError(f'Operation ID collision: {ids[operation_id]} and {method} {path}')
            ids[operation_id] = f'{method} {path}'
            operation['operationId'] = operation_id
    return spec


def load_specs(spec_dir):
    """逐服务读 spec。不写任何文件 —— 冲突检测必须先于落盘。"""
    specs={}
    for service,port in SERVICES:
        if spec_dir:
            spec=json.loads((spec_dir/f'{service}.openapi.json').read_text())
        else:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/v3/api-docs') as response:
                spec=json.load(response)
        # The exported server URL depends on where the spec came from (live port vs MockMvc), so pin it
        # to a same-origin value. Otherwise the same code generates two different contracts and CI's
        # "no drift" check fails depending on how the spec was fetched.
        spec['servers']=[{'url':'/','description':'Same-origin service base'}]
        specs[service]=normalize_operation_ids(spec)
    return specs


def collisions(specs):
    """返回 (identical, differing)：跨服务重名的类型名。

    identical = 各服务定义逐字节相同，合并不会吃掉任何东西。
    differing = 至少两处结构不同，合并必然丢字段 —— 这才是真正的地雷。
    """
    owners={}
    for service,_ in SERVICES:  # 显式按 SERVICES 顺序，holder 的先后 = 覆盖的先后
        spec=specs.get(service,{})
        for name,schema in spec.get('components',{}).get('schemas',{}).items():
            owners.setdefault(name,[]).append((service,schema))
    identical,differing=[],[]
    for name,holders in sorted(owners.items()):
        if len(holders)<2: continue
        canonical=lambda schema: json.dumps(schema,sort_keys=True,ensure_ascii=False)
        first=canonical(holders[0][1])
        (identical if all(canonical(s)==first for _,s in holders[1:]) else differing).append((name,holders))
    return identical,differing


def _fields(schema): return list((schema.get('properties') or {}).keys())


def _display(path):
    """仓库内显示相对路径，仓库外（--out-dir 指向别处时）显示绝对路径，不许崩。"""
    try: return str(path.relative_to(root))
    except ValueError: return str(path)


def describe(name,holders,bound):
    """人可读的对账：每个服务定义了哪些字段，桶文件最终绑定的是哪一份，谁被挡住。

    分文件之后两份定义都活在自己的文件里，但**消费者 import 的是桶文件**，
    显式消解行只暴露一份，另一份独有的字段就在消费端「属性不存在」。
    """
    lines=[f'  x {name}']
    for service,schema in holders:
        lines.append(f'      {service:<10} {len(_fields(schema))} 字段: {", ".join(_fields(schema))}')
    lines.append(f'      桶文件绑定: {bound}（按 SERVICES 顺序取第一份）')
    bound_schema=dict(holders)[bound]
    bound_fields=set(_fields(bound_schema))
    for service,schema in holders:
        if service==bound: continue
        mine=set(_fields(schema))
        hidden=sorted(mine-bound_fields)      # 这个服务独有、消费端拿不到的字段
        extra=sorted(bound_fields-mine)       # 消费端会看到但这个服务从没声明过的字段
        if hidden: lines.append(f'      -> {service} 独有、消费端拿不到: {", ".join(hidden)}')
        if extra: lines.append(f'      -> {service} 消费端会凭空多出: {", ".join(extra)}')
    return lines


def load_allowlist():
    """allowlist 现在不只是「放行」，还要说清**桶文件绑定哪一份**，否则 barrel 会 TS2308。"""
    if not ALLOWLIST.exists(): return {}
    entries=json.loads(ALLOWLIST.read_text()).get('allowed',[])
    return {e['name']:e.get('winner') for e in entries if e.get('name')}


def gate(specs):
    """把 specs 判成一份可断言的结论。测试直接打这个函数，不走 main。"""
    identical,differing=collisions(specs)
    allowed=load_allowlist()
    bound={name:(allowed.get(name) or holders[0][0]) for name,holders in identical+differing}
    return {
        'identical': identical,
        'differing': differing,
        'allowed': allowed,
        'blocking': [(n,h) for n,h in differing if n not in allowed],
        'accepted': [(n,h) for n,h in differing if n in allowed],
        'bound': bound,
        'stale': sorted(set(allowed)-{n for n,_ in identical+differing}),
    }


def ts(s):
    if s.get('nullable'): return ts({k:v for k,v in s.items() if k!='nullable'})+' | null'
    if 'anyOf' in s: return ' | '.join(ts(x) for x in s['anyOf'])
    if '$ref' in s: return s['$ref'].split('/')[-1]
    if 'enum' in s: return ' | '.join(json.dumps(v) for v in s['enum'])
    t=s.get('type')
    if isinstance(t,list): return ' | '.join(ts({**s,'type':x}) for x in t)
    if t=='array': return f'Array<{ts(s["items"])}>'
    if t=='object': return '{ '+ '; '.join(f'{json.dumps(k)}: {ts(v)}' for k,v in s.get('properties',{}).items())+' }'
    return {'string':'string','integer':'number','number':'number','boolean':'boolean','null':'null'}.get(t,'unknown')


GENERATED='// Generated by tools/codegen/catalog-contracts.py from Spring OpenAPI. DO NOT EDIT.\n'


def render_types(schemas):
    text=GENERATED
    for name,schema in sorted(schemas.items()): text+=f'export type {name} = {ts(schema)};\n'
    return text


def render_index(verdict):
    """桶文件：把各服务文件汇到一个入口。

    `export *` 遇到同名成员会报 TS2308（实测过），所以每一条重名都要显式指定一份，
    否则光是为了通过编译就得手工消歧，那等于把判定挪到了编译期而不是生成期。
    """
    text=GENERATED
    text+='// 每个服务一个文件：同名 DTO 不再互相覆盖。下面只是把它们汇到一个入口。\n'
    for service,_ in SERVICES: text+=f"export * from './contracts/{service}';\n"
    identical=verdict['identical']
    if identical or verdict['accepted']:
        bound=verdict['bound']
        text+='\n// 重名的类型：显式指定一份，消除 export * 的歧义。\n'
        for name,_ in identical:
            text+=f"export type {{ {name} }} from './contracts/{bound[name]}';\n"
        for name,_ in verdict['accepted']:
            text+=f"export type {{ {name} }} from './contracts/{bound[name]}';\n"
    return text


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spec-dir',type=Path,help='Spring OpenAPI snapshots exported by the service contract tests')
    parser.add_argument('--out-dir',type=Path,help='生成物输出目录（默认 packages/api-contracts/src）；测试用，避免污染真实契约')
    parser.add_argument('--no-prettier',action='store_true',help='跳过 prettier（测试用，避免依赖 node_modules）')
    args=parser.parse_args()
    target=args.out_dir or root/'packages/api-contracts/src'

    specs=load_specs(args.spec_dir)
    verdict=gate(specs)

    if verdict['blocking']:
        print('跨服务同名 DTO：字段级覆盖，已中止（未写出任何文件）\n',file=sys.stderr)
        for name,holders in verdict['blocking']:
            print('\n'.join(describe(name,holders,verdict['bound'][name])),file=sys.stderr); print(file=sys.stderr)
        print('修法二选一：',file=sys.stderr)
        print('  1. 给其中一个改名，带服务/模块前缀（例：ProcessingDownstreamGap）—— 推荐',file=sys.stderr)
        print(f'  2. 确实要接受，写进 {_display(ALLOWLIST)}，写清理由与 winner（哪一份对消费端可见）',file=sys.stderr)
        print(f'\n背景：{BACKLOG}',file=sys.stderr)
        return 1

    target.mkdir(parents=True,exist_ok=True)
    contracts=target/'contracts'
    contracts.mkdir(parents=True,exist_ok=True)
    written=[]
    total=0
    for service,spec in specs.items():
        snapshot=target/f'{service}.openapi.json'
        # sort_keys=True：快照的键序**规范化**成字典序，而不是继承输入的键序。
        # 否则同一份契约会随「输入 spec 的键序」变化 —— CI 每次用活 spec 重新生成，
        # 键序取决于服务返回的 JSON 顺序；并入驱动器求并后写出的键序是插入序；
        # 两者永远对不上 ⇒ 契约闸一直红。规范化之后，生成器与驱动器的输出才可能逐字节相等。
        snapshot.write_text(json.dumps(spec,ensure_ascii=False,indent=2,sort_keys=True)+'\n')
        written.append(snapshot)
        schemas=spec.get('components',{}).get('schemas',{})
        total+=len(schemas)
        per_service=contracts/f'{service}.ts'
        per_service.write_text(render_types(schemas))
        written.append(per_service)
    index=target/'index.ts'
    index.write_text(render_index(verdict))
    written.append(index)

    legacy=target/'catalog.ts'
    if legacy.exists():
        print(f'  WARN  {_display(legacy)} 是分文件之前的扁平产物，'
              '留着会让走旧路径的 import 拿到过期类型且不报错 —— 请删掉它并改用 index',file=sys.stderr)

    print(f'Generated {total} DTOs in {len(specs)} per-service files, {len(specs)} OpenAPI snapshots and one barrel')
    for name,holders in verdict['identical']:
        services=', '.join(s for s,_ in holders)
        print(f'  note  {name}: {services} 定义完全相同，两边都在各自的文件里活着，'
              f'桶文件显式取「{holders[0][0]}」那一份（谁改了一边就会变字段级覆盖）')
    for name,holders in verdict['accepted']:
        services=', '.join(s for s,_ in holders)
        print(f'  ALLOW {name}: {services} 结构不同，按 allowlist 声明保留「{holders[-1][0]}」的版本')
    for name in verdict['stale']:
        print(f'  WARN  allowlist 里的 {name} 现在没有重名了，可以删掉这条')

    if not args.no_prettier:
        subprocess.run(['pnpm','exec','prettier','--write',*[str(p) for p in written]],cwd=root,check=True,stdout=subprocess.DEVNULL)
    return 0


if __name__=='__main__': sys.exit(main())
