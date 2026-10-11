#!/usr/bin/env python3
"""catalog-contracts.py 的跨服务重名闸门测试。

跑法：python3 -m unittest discover -s tools/codegen -p 'test_*.py'

最重要的一条是 CollisionGateSubprocess.test_字段级覆盖时脚本非零退出且不落盘 ——
在加这道闸之前，同样这组 fixture 会 exit 0 并把 loser 的类型静默顶掉。
所以这条测试就是「静默覆盖变响亮失败」的回归护栏；它自己必须先能在坏输入上转红。
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

    def test_describe_reports_the_winner_and_the_vanishing_fields(self):
        """覆盖方向标定：all_schemas.update() 按 SERVICES 顺序覆盖，**后跑到的赢**。

        trade 排在 warehouse 之后，所以 warehouse 的定义才是被吃掉的那一份。
        这条断言曾经抓出过方向写反的 bug（报告说 trade 丢字段，实际是 warehouse 丢）。
        """
        specs={'warehouse':spec({LOSER_TYPE:obj('a','b','onlyWarehouse')}),
               'trade':spec({LOSER_TYPE:obj('a','b','onlyTrade')})}
        (name,holders),=cc.gate(specs)['blocking']
        text='\n'.join(cc.describe(name,holders))
        self.assertIn('最终生效: trade',text)
        self.assertIn('warehouse 独有、合并后消失: onlyWarehouse',text)
        self.assertIn('warehouse 会凭空多出: onlyTrade',text)
        self.assertNotIn('trade 独有',text)
        self.assertNotIn('trade 会凭空多出',text)

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
                             '中止时不得写出任何文件：半个 catalog.ts 比不写更糟')

    def test_干净输入时生成成功且保留每个服务的类型(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({'WhOnly':obj('a')}),
                                                    'trade':spec({'TradeOnly':obj('b','c')})})
            out_dir=Path(tmp)/'out'
            result=run_script(spec_dir,out_dir)
            self.assertEqual(result.returncode,0,result.stderr)
            catalog=(out_dir/'catalog.ts').read_text()
            self.assertIn('export type WhOnly = { "a": string };',catalog)
            self.assertIn('export type TradeOnly = { "b": string; "c": string };',catalog)
            self.assertTrue((out_dir/'trade.openapi.json').exists())
            self.assertTrue((out_dir/'warehouse.openapi.json').exists())

    def test_同名但结构相同时放行并给出提示(self):
        with tempfile.TemporaryDirectory() as tmp:
            spec_dir=write_specs(Path(tmp)/'specs',{'warehouse':spec({LOSER_TYPE:obj('a','b')}),
                                                    'trade':spec({LOSER_TYPE:obj('a','b')})})
            result=run_script(spec_dir,Path(tmp)/'out')
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn(LOSER_TYPE,result.stdout)


if __name__=='__main__':
    unittest.main()
