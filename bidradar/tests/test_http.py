import csv
import http.client
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import app
import core

class HttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.old=core.DB
        core.DB=Path(self.tmp.name)/'db.sqlite'
        core.init()
        self.server=app.ThreadingHTTPServer(('127.0.0.1',0),app.Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join()
        core.DB=self.old;self.tmp.cleanup()
    def request(self,method,path,body=None,headers=None):
        conn=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        h={'Content-Type':'application/json'}
        h.update(headers or {})
        conn.request(method,path,json.dumps(body) if body is not None else None,h)
        r=conn.getresponse(); result=(r.status,r.read(),dict(r.getheaders()));conn.close()
        return result
    def test_home_and_full_local_flow(self):
        status,body,headers=self.request('GET','/')
        self.assertEqual(status,200); self.assertIn('标讯雷达',body.decode())
        self.assertIn("frame-ancestors 'none'",headers['Content-Security-Policy'])
        status,body,_=self.request('POST','/api/topics',{'name':'测试','keywords':'OPC','region':'','excludes':''})
        self.assertEqual(status,200);topic_id=json.loads(body)['id']
        self.assertEqual(self.request('POST','/api/demo',{})[0],200)
        state=json.loads(self.request('GET','/api/state')[1])
        self.assertEqual(len(state['projects']),4)
        self.assertFalse('DEEPSEEK_API_KEY' in state['config'])
        pid=state['projects'][0]['id']
        self.assertEqual(self.request('POST','/api/projects',{'id':pid,'favorite':True,'notes':'本地测试'})[0],200)
        self.assertIn('本地测试',self.request('GET','/api/export')[1].decode())
        self.assertEqual(self.request('POST','/api/topics/delete',{'id':topic_id})[0],200)
        state=json.loads(self.request('GET','/api/state')[1])
        self.assertFalse(state['topics']);self.assertFalse(state['matches'])
        self.assertEqual(len(state['projects']),4)

    def test_follow_buyer_and_link_notice(self):
        self.assertEqual(self.request('POST', '/api/demo', {})[0], 200)
        state = json.loads(self.request('GET', '/api/state')[1])
        project_id = state['projects'][0]['id']
        name = '示例采购单位'

        status, body, _ = self.request('POST', '/api/buyers', {'name': name})
        self.assertEqual(status, 200)
        buyer_id = json.loads(body)['id']
        status, _, _ = self.request('POST', '/api/projects',
                                    {'id': project_id, 'buyer_name': name, 'notes': '已核对'})
        self.assertEqual(status, 200)
        state = json.loads(self.request('GET', '/api/state')[1])
        project = next(p for p in state['projects'] if p['id'] == project_id)
        buyer = next(b for b in state['buyers'] if b['id'] == buyer_id)
        self.assertEqual((project['buyer_id'], project['buyer_name'], project['buyer_source'], project['notes']),
                         (buyer_id, name, 'manual', '已核对'))
        self.assertEqual((buyer['followed'], buyer['project_count']), (1, 1))
        status, body, _ = self.request('GET', f'/api/buyers/projects?buyer={buyer_id}')
        self.assertEqual(status, 200)
        self.assertEqual([p['id'] for p in json.loads(body)['projects']], [project_id])
        self.assertIsNone(json.loads(body)['next_before'])

        self.assertEqual(self.request('POST', '/api/buyers', {'id': buyer_id, 'followed': False})[0], 200)
        state = json.loads(self.request('GET', '/api/state')[1])
        self.assertFalse(next(b for b in state['buyers'] if b['id'] == buyer_id)['followed'])
        self.assertEqual(next(p for p in state['projects'] if p['id'] == project_id)['buyer_id'], buyer_id)

    def test_intent_scan_keeps_official_detail_results(self):
        topic = {'name': '运营服务', 'keywords': '运营服务'}
        _, body, _ = self.request('POST', '/api/topics', topic)
        topic_id = json.loads(body)['id']
        base = 'https://cgyx.ccgp.gov.cn/cgyx/pub/'
        notice = {'title': '创新中心公开计划', 'content': '拟采购运营服务', 'url': base + 'details?groupId=fixture'}
        results = [notice, {**notice, 'url': base + 'pubSearch'},
                   {**notice, 'url': 'https://example.org/cgyx/pub/details?groupId=fixture'}]
        with patch.dict(os.environ, {'DEEPSEEK_API_KEY': 'fixture-key'}), \
                patch.object(core, 'deepseek_search', return_value=results) as search:
            status, body, _ = self.request('POST', '/api/scan/intents', {'id': topic_id})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['count'], 1)
        search.assert_called_once_with('site:cgyx.ccgp.gov.cn 采购意向 运营服务', 'fixture-key', False)
        state = json.loads(self.request('GET', '/api/state')[1])
        self.assertEqual([(p['url'], p['notice_kind']) for p in state['projects']], [(notice['url'], 'intent')])
        self.assertEqual(state['runs'][0]['kind'], 'intent')

    def test_qcc_search_imports_via_http_without_exposing_credentials(self):
        _, body, _ = self.request('POST', '/api/topics', {'name':'运营', 'keywords':'运营'})
        topic_id = json.loads(body)['id']
        response = {'items':[{'Id':'vendor-1', 'Title':'运营服务招标公告',
                              'ContentUrl':'https://example.gov.cn/notice/1',
                              'BudgetAmt':'30万元', 'BidProgressList':['招标公告']}],
                    'total':1, 'page':1}
        with patch.dict(os.environ, {'QCC_APP_KEY':'fixture-app-key', 'QCC_SECRET_KEY':'fixture-secret'}), \
                patch.object(core.qcc, 'search', return_value=response) as search:
            status, body, _ = self.request('POST', '/api/qcc/search',
                                           {'id':topic_id, 'keyword':'运营', 'page_index':1})
            self.assertEqual(status, 200)
            self.assertEqual((json.loads(body)['new'], json.loads(body)['count']), (1, 1))
            state_body = self.request('GET', '/api/state')[1]
        search.assert_called_once()
        self.assertNotIn('fixture-secret', state_body.decode())
        self.assertNotIn('fixture-app-key', state_body.decode())
        project = json.loads(state_body)['projects'][0]
        self.assertEqual((project['origin'], project['qcc_budget']), ('qcc', '30万元'))

    def test_csp_allows_vue_runtime_compiler(self):
        # 前端模板全部由 Vue 运行时编译器生成（index.html 的 #app 是空挂载点）。
        # Vue 的 compileToFunction 内部调用 Function(code)，CSP 若缺少 'unsafe-eval'
        # 浏览器会拒绝执行，导致 #app 永远为空——即整页白屏。
        _,body,headers=self.request('GET','/')
        csp=headers['Content-Security-Policy']
        self.assertIn("'unsafe-eval'",csp)
        self.assertIn('script-src',csp)
        self.assertIn('id="app"',body.decode())
    def test_export_csv_by_topic(self):
        # 演示数据4条：全部含 OPC，仅1条含「盐城」
        self.assertEqual(self.request('POST','/api/demo',{})[0],200)
        _,body,_=self.request('POST','/api/topics',{'name':'全部主题','keywords':'OPC','region':'','excludes':''})
        all_id=json.loads(body)['id']
        _,body,_=self.request('POST','/api/topics',{'name':'盐城主题','keywords':'盐城','region':'','excludes':''})
        yc_id=json.loads(body)['id']
        def rows(path):
            status,body,_=self.request('GET',path)
            self.assertEqual(status,200,path)
            return list(csv.reader(io.StringIO(body.decode('utf-8-sig'))))
        # 新增「命中主题」列，且全部导出仍为4条
        every=rows('/api/export')
        self.assertEqual(every[0][-1],'命中主题')
        self.assertEqual(len(every)-1,4)
        self.assertTrue(all(r[-1] for r in every[1:]))
        # OPC 主题命中全部4条
        self.assertEqual(len(rows('/api/export?topic=%d'%all_id))-1,4)
        # 盐城主题只导出命中的1条；该条也命中「全部主题」，故命中主题列含两个名字
        yc=rows('/api/export?topic=%d'%yc_id)
        self.assertEqual(len(yc)-1,1)
        self.assertIn('盐城主题',yc[1][-1])
        self.assertIn('盐城',yc[1][0])
        # 参数校验：非数字400，不存在的主题404
        self.assertEqual(self.request('GET','/api/export?topic=abc')[0],400)
        self.assertEqual(self.request('GET','/api/export?topic=999999')[0],404)
    def test_foreign_host_and_origin_denied(self):
        self.assertEqual(self.request('GET','/api/state',headers={'Host':'attacker.example'})[0],403)
        self.assertEqual(self.request('POST','/api/demo',{},headers={'Origin':'https://attacker.example'})[0],403)
        self.assertFalse(core.state()['projects'])
    def test_unknown_route_and_invalid_json_type(self):
        self.assertEqual(self.request('GET','/../core.py')[0],404)
        self.assertEqual(self.request('POST','/api/topics',[])[0],400)
        self.assertEqual(self.request('POST','/api/settings',{'interval_hours':1})[0],400)
    def test_frontend_assets_served(self):
        status,body,headers=self.request('GET','/')
        self.assertEqual(status,200)
        html=body.decode()
        for asset in ['/vendor/vue.global.prod.js','/vendor/element-plus.js','/vendor/element-plus.css','/app.js','/style.css']:
            self.assertIn(asset,html)
        self.assertIn('id="app"',html)
        expected={
            '/vendor/vue.global.prod.js':'text/javascript',
            '/vendor/icons.js':'text/javascript',
            '/vendor/element-plus.js':'text/javascript',
            '/vendor/element-plus.css':'text/css',
            '/app.js':'text/javascript',
            '/style.css':'text/css',
        }
        for path,mime in expected.items():
            status,body,headers=self.request('GET',path)
            self.assertEqual(status,200,'%s 未返回 200'%path)
            self.assertGreater(len(body),0,'%s 内容为空'%path)
            self.assertIn(mime,headers['Content-Type'])
    def test_vendor_path_traversal_blocked(self):
        for path in ['/vendor/../core.py','/vendor/..%2fcore.py','/vendor/nope.js','/vendor/data/bidradar.db']:
            self.assertEqual(self.request('GET',path)[0],404,'%s 应被拒绝'%path)
    def test_mutations_rejected_during_scan(self):
        core.SEARCH_LOCK.acquire()
        try:
            self.assertEqual(self.request('POST','/api/topics',{'name':'test','keywords':'OPC'})[0],400)
        finally:
            core.SEARCH_LOCK.release()

if __name__=='__main__':unittest.main()
