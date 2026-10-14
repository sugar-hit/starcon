#!/usr/bin/env python3
"""catalog-contracts.py 的分文件输出与跨服务重名闸门测试。

跑法：python3 -m unittest discover -s tools/codegen -p 'test_*.py'

两条主线：
  1. 字段级重名必须**响亮失败** —— CollisionGateSubprocess.test_字段级覆盖时非零退出且不落盘。
     在加这道闸之前，同样这组 fixture 会 exit 0 并把 loser 的类型静默顶掉。
  2. 分文件之后同名不再有静默路径 —— 两份定义各自留在 contracts/<service>.ts，
     桶文件必须显式绑定一份，忘了就是 TS2308 编译错误（实测过，不是猜的）。
"""
import importlib.util, json, subprocess, sys, tempfile, unittest
from pathlib import Path

SCRIPT=Path(__file__).resolve().parent/'catalog-contracts.py'
ROOT=SCRIPT.parents[2]

def _load():
    spec=importlib.util.spec_from_file_location('catalog_contracts',SCRIPT)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

cc=_load()

# 只在 fixture 里出现的名字：真实 allowlist 无论怎么改都不会命中它，
# 这样子进程测试不会被「有人往 allowlist 里加了一条」意外解绑。
LOSER_TYPE='ZzCollisionProbe'


def obj(*fields):
    return {'type':'object','properties':{f:{'type':'string'} for f in fields}}


def spec(schemas):
    return {'openapi':'3.0.3','info':{'title':'fixture','version':'1'},'components':{'schemas':schemas}}


def write_specs(directory, per_service):
    directory.mkdir(parents=True,exist_ok=True)
    for service,_ in cc.SERVICES:
        (directory/f'{service}.openapi.json').write_text(json.dumps(per_service.get(service,spec({}))))
    return directory


def run_script(spec_dir, out_dir):
    return subprocess.run([sys.executable,str(SCRIPT),'--spec-dir',str(spec_dir),
                           '--out-dir',str(out_dir),'--no-prettier'],
                          capture_output=True,text=True,cwd=ROOT)


class CollisionGate(unittest.TestCase):
    """gate() 的分级判定：相同=提示，结构不同=拦。"""

    def test_identical_cross_service_is_not_blocking(self):
        specs={'warehouse':spec({'Same':obj('a','b')}),'trade':spec({'Same':obj('a','b')})}
        verdict=cc.gate(specs)
        self.assertEqual([n for n,_ in verdict['identical']],['Same'])
        self.assertEqual(verdict['differing'],[])
        self.assertEqual(verdict['blocking'],[])

    def test_identical_by_field_order_counts_as_identical(self):
        specs={'warehouse':spec({'Same':obj('a','b')}),'trade':spec({'Same':obj('b','a')})}
        self.assertEqual(cc.gate(specs)['blocking'],[])

    def test_differing_cross_service_is_blocking(self):
        specs={'warehouse':spec({LOSER_TYPE:obj('a','b')}),'trade':spec({LOSER_TYPE:obj('a','b','c')})}
        verdict=cc.gate(specs)
        self.assertEqual([n for n,_ in verdict['blocking']],[LOSER_TYPE],
                         '闸门对已知坏输入必须转红，否则这条护栏是自证的假绿')

    def test_no_collision_is_quiet(self):
        specs={'warehouse':spec({'A':obj('x')}),'trade':spec({'B':obj('y')})}
        verdict=cc.gate(specs)
        self.assertEqual(verdict['identical'],[])
        self.assertEqual(verdict['differing'],[])
        self.assertEqual(verdict['blocking'],[])

    def test_within_one_service_is_never_a_collision(self):
        specs={'trade':spec({'Only':obj('a'),'Also':obj('b')})}
        self.assertEqual(cc.gate(specs)['blocking'],[])

    def test_describe_reports_the_bound_service_and_the_hidden_fields(self):
        """桶文件绑定的是 SERVICES 顺序里**第一个**服务的版本。

        分文件之后两份定义都活在自己的文件里，但消费者 import 的是桶文件，
        只有被绑定的那一份可见 —— 所以报告要说清「谁被挡住、挡掉了哪些字段」。
        （这条曾经抓出过一个方向写反的 bug：分文件前的扁平合并是**后**跑的赢。）
        """
        specs={'warehouse':spec({LOSER_TYPE:obj('a','b','onlyWarehouse')}),
               'trade':spec({LOSER_TYPE:obj('a','b','onlyTrade')})}
        (name,holders),=cc.gate(specs)['blocking']
        text='\n'.join(cc.describe(name,holders,cc.gate(specs)['bound'][name]))
        self.assertIn('桶文件绑定: warehouse',text)
        self.assertIn('trade 独有、消费端拿不到: onlyTrade',text)
        self.assertIn('trade 消费端会凭空多出: onlyWarehouse',text)
        self.assertNotIn('warehouse 消费端会凭空多出',text)

    def test_allowlist_turns_a_blocking_collision_into_an_accepted_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            allow=Path(tmp)/'allow.json'
            allow.write_text(json.dumps({'allowed':[{'name':LOSER_TYPE,'reason':'test'}]}))
            original,cc.ALLOWLIST=cc.ALLOWLIST,allow
            try:
                specs={'warehouse':spec({LOSER_TYPE:obj('a')}),'trade':spec({LOSER_TYPE:obj('a','b')})}
                verdict=cc.gate(specs)
            finally:
                cc.ALLOWLIST=original
        self.assertEqual(verdict['blocking'],[])
        self.assertEqual([n for n,_ in verdict['accepted']],[LOSER_TYPE])

    def test_stale_allowlist_entry_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            allow=Path(tmp)/'allow.json'
            allow.write_text(json.dumps({'allowed':[{'name':'NoLongerCollides','reason':'test'}]}))
            original,cc.ALLOWLIST=cc.ALLOWLIST,allow
            try:
                verdict=cc.gate({'warehouse':spec({'A':obj('x')}),'trade':spec({'B':obj('y')})})
            finally:
                cc.ALLOWLIST=original
        self.assertEqual(verdict['stale'],['NoLongerCollides'])


class CollisionGateSubprocess(unittest.TestCase):
    """端到端：脚本本身必须非零退出，且**一个文件都不写**。"""

    def test_字段级覆盖时非零退出且不落盘(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({LOSER_TYPE:obj('a','b')}),
                                                    'trade':spec({LOSER_TYPE:obj('a','b','c')})})
            out_dir=Path(tmp)/'out'
            result=run_script(spec_dir,out_dir)
            self.assertEqual(result.returncode,1,result.stderr)
            self.assertIn(LOSER_TYPE,result.stderr)
            self.assertFalse(out_dir.exists() and any(out_dir.iterdir()),
                             '中止时不得写出任何文件：半个契约目录比不写更糟')

    def test_每服务一个文件且各自只含自己的类型(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({'WhOnly':obj('a')}),
                                                    'trade':spec({'TradeOnly':obj('b','c')})})
            out_dir=Path(tmp)/'out'
            result=run_script(spec_dir,out_dir)
            self.assertEqual(result.returncode,0,result.stderr)
            warehouse=(out_dir/'contracts/warehouse.ts').read_text()
            trade=(out_dir/'contracts/trade.ts').read_text()
            self.assertIn('export type WhOnly = { "a": string };',warehouse)
            self.assertNotIn('TradeOnly',warehouse,'一个服务的文件里不该混入别的服务的类型')
            self.assertIn('export type TradeOnly = { "b": string; "c": string };',trade)
            self.assertNotIn('WhOnly',trade)
            # 每个服务都要有文件，哪怕它一个类型都没有（否则桶文件会指向不存在的模块）
            for service,_ in cc.SERVICES:
                self.assertTrue((out_dir/f'contracts/{service}.ts').exists(),f'{service} 缺文件')
            self.assertTrue((out_dir/'index.ts').exists())
            self.assertTrue((out_dir/'trade.openapi.json').exists())

    def test_不再生成旧的扁平catalog_ts(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({'WhOnly':obj('a')})})
            out_dir=Path(tmp)/'out'
            run_script(spec_dir,out_dir)
            self.assertFalse((out_dir/'catalog.ts').exists(),
                             '旧的扁平产物必须消失：留着会让走旧路径的 import 静默拿到过期类型')

    def test_同名但结构相同时两边都活着且桶文件显式消歧(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({LOSER_TYPE:obj('a','b')}),
                                                    'trade':spec({LOSER_TYPE:obj('a','b')})})
            out_dir=Path(tmp)/'out'
            result=run_script(spec_dir,out_dir)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn(LOSER_TYPE,result.stdout)
            for service in ('warehouse','trade'):
                self.assertIn(LOSER_TYPE,(out_dir/f'contracts/{service}.ts').read_text(),
                              '分文件之后两份定义都必须留在自己的文件里')
            index=(out_dir/'index.ts').read_text()
            self.assertIn("export * from './contracts/warehouse';",index)
            self.assertIn("export * from './contracts/trade';",index)
            self.assertIn("export type { ZzCollisionProbe } from './contracts/warehouse';",index,
                          '缺了这行就是 TS2308 编译错误')

    def test_放行的有损重名也必须在桶文件里绑定一份(self):
        """allowlist 放行后如果桶文件不绑定，export * 撞名就是 TS2308，编译直接红。"""
        with tempfile.TemporaryDirectory() as tmp:
            allow=Path(tmp)/'allow.json'
            allow.write_text(json.dumps({'allowed':[{'name':LOSER_TYPE,'reason':'test','winner':'trade'}]}))
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({LOSER_TYPE:obj('a')}),
                                                    'trade':spec({LOSER_TYPE:obj('a','b')})})
            out_dir=Path(tmp)/'out'
            original_allow,original_argv=cc.ALLOWLIST,sys.argv
            cc.ALLOWLIST=allow
            sys.argv=['catalog-contracts.py','--spec-dir',str(spec_dir),
                      '--out-dir',str(out_dir),'--no-prettier']
            try:
                code=cc.main()
            finally:
                cc.ALLOWLIST,sys.argv=original_allow,original_argv
            self.assertEqual(code,0)
            index=(out_dir/'index.ts').read_text()
        self.assertIn(f"export type {{ {LOSER_TYPE} }} from './contracts/trade';",index,
                      'allowlist 指定的 winner 必须出现在桶文件里')

    def test_残留的旧扁平产物会被警告(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({'WhOnly':obj('a')})})
            out_dir=Path(tmp)/'out'
            out_dir.mkdir(parents=True)
            (out_dir/'catalog.ts').write_text('// 上一轮遗留\n')
            result=run_script(spec_dir,out_dir)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('catalog.ts',result.stderr)
            self.assertIn('WARN',result.stderr)


if __name__=='__main__':
    unittest.main()


class OperationIdNormalization(unittest.TestCase):
    def test_discovery_suffixes_do_not_change_route_identifiers(self):
        first = {'paths': {'/warehouse/tasks/{id}': {'get': {'operationId': 'detail_2'}},
                           '/warehouse/tasks': {'get': {'operationId': 'page_8'}}}}
        second = {'paths': {'/warehouse/tasks': {'get': {'operationId': 'page_2'}},
                            '/warehouse/tasks/{id}': {'get': {'operationId': 'detail_9'}}}}
        self.assertEqual(cc.normalize_operation_ids(first), cc.normalize_operation_ids(second))
        self.assertEqual(first['paths']['/warehouse/tasks/{id}']['get']['operationId'],
                         'get_warehouse_tasks_id')

    def test_methods_on_the_same_path_have_distinct_identifiers(self):
        data = {'paths': {'/tasks': {'get': {}, 'post': {}, 'parameters': []}}}
        cc.normalize_operation_ids(data)
        self.assertNotEqual(data['paths']['/tasks']['get']['operationId'],
                            data['paths']['/tasks']['post']['operationId'])
        self.assertEqual(data['paths']['/tasks']['parameters'], [])

    def test_normalized_path_collision_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Operation ID collision'):
            cc.normalize_operation_ids({'paths': {'/a-b': {'get': {}}, '/a_b': {'get': {}}}})
