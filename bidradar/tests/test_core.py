import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import core
import crawler

class CoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.old=core.DB
        core.DB=Path(self.tmp.name)/'test.db'
        core.init()
        self.topic=core.save_topic(dict(name='OPC',keywords='OPC,一人公司',excludes='OPC协议,装修',region=''))
    def tearDown(self):
        core.DB=self.old
        self.tmp.cleanup()
    def result(self,**kwargs):
        return dict(title='OPC社区运营招标公告',content='江苏盐城采购OPC创业服务',url='https://example.gov.cn/a?x=1',**kwargs)
    def test_is_notice_keeps_real_announcement(self):
        self.assertEqual(core.is_notice('无锡市青少年发展服务中心青年夜校成交公告-采购与招标网','https://www.chinabidding.cn/zbgs/U-1.html'),1)
        # 站点词出现在标题末尾不算聚合页，避免误杀招标网转载的正式公告
        self.assertEqual(core.is_notice('靖江市城乡建设有限公司电商创业中心采购公告-招标采购投标服务平台','https://www.gxxszb.com/Html/1638603.html'),1)
    def test_is_notice_flags_portal_and_aggregator(self):
        self.assertEqual(core.is_notice('常州市政府采购网','https://zfcg.changzhou.gov.cn/html/ns/x.html'),0)
        self.assertEqual(core.is_notice('江苏省教育招投标_江苏省教育中标公告_江苏省教育招标网','https://a.bidchance.com/tsp_1.html'),0)
        self.assertEqual(core.is_notice('第266页_江苏招标-江苏省政府招标网','http://jsszbw.gdtzb.com/v1/266'),0)
        self.assertEqual(core.is_notice('开通 黄金会员 ，即可查看实时商机信息','https://www.chinamae.com/p/1.html'),0)
    def test_is_notice_flags_news_media(self):
        self.assertEqual(core.is_notice('青年夜校“酿”出共治新气象 - 今日头条','https://www.toutiao.com/article/1/'),0)
        self.assertEqual(core.is_notice('启动校地共建“青年夜校”','https://jsnews.jschina.com.cn/jsyw/1.shtml'),0)
    def test_scan_marks_noise_flag(self):
        def provider(q):
            return [self.result(),
                    dict(title='常州市政府采购网',content='江苏政府采购 招标 采购 OPC 一人公司',url='https://zfcg.changzhou.gov.cn/x.html')]
        core.scan(self.topic,provider)
        flags={p['title']:p['is_notice'] for p in core.state()['projects']}
        self.assertEqual(flags['OPC社区运营招标公告'],1)
        self.assertEqual(flags['常州市政府采购网'],0)
    def test_reclassify_backfills_existing_rows(self):
        def provider(q):
            r=self.result(); r['title']='常州市政府采购网'; return [r]
        core.scan(self.topic,provider)
        with core.connect() as db:
            db.execute('UPDATE projects SET is_notice=1')
        self.assertEqual(core.reclassify(),1)
        self.assertEqual(core.state()['projects'][0]['is_notice'],0)
    def test_filter_exclusions_and_region(self):
        topic=dict(keywords='OPC,一人公司',excludes='OPC协议',region='江苏,南京')
        hit=core.evaluate(topic,'OPC招标','江苏')
        self.assertIsNotNone(hit)
        self.assertIn('地区命中：江苏',hit[1])
        self.assertIsNone(core.evaluate(topic,'OPC协议招标','江苏'))
        # 地区未命中不再丢弃：降分 + 标注待核实，避免截断摘要误杀本省公告
        miss=core.evaluate(topic,'OPC招标','上海')
        self.assertIsNotNone(miss)
        self.assertIn('地区待核实',miss[1])
        self.assertLess(miss[0],hit[0])
        self.assertIsNone(core.evaluate(topic,'OPC介绍','江苏'))
    def test_region_unlimited_equals_empty(self):
        # 「不限/全国/全部」不是地名：不能拼进 query，也不能让线索白标地区待核实
        for placeholder in ('不限','全国','全部','不限地区'):
            topic=dict(keywords='OPC,一人公司',excludes='OPC协议',region=placeholder)
            self.assertEqual(core.region_words(topic['region']),[])
            self.assertEqual(core.queries(topic),core.queries(dict(topic,region='')))
            self.assertNotIn(placeholder,core.queries(topic)[0])
            got=core.evaluate(topic,'OPC招标','江苏盐城采购OPC服务')
            self.assertEqual(got,core.evaluate(dict(topic,region=''),'OPC招标','江苏盐城采购OPC服务'))
            self.assertNotIn('地区',got[1])
    def test_region_unlimited_mixed_with_real_city(self):
        # 混写时只保留真实地名
        self.assertEqual(core.region_words('不限,江苏，全国;南京'),['江苏','南京'])
        self.assertEqual(core.queries(dict(keywords='OPC',region='不限,江苏')),
                         ['OPC 招标 采购', 'OPC 招标 采购 江苏'])
    def test_scan_keeps_up_to_search_count(self):
        # 接口多给 10 条，scan 只保留前 SEARCH_COUNT 条（防止某个查询把额度吃满）
        extra=core.SEARCH_COUNT+10
        def many(q):
            return [dict(title='OPC社区运营招标公告',content='江苏盐城采购OPC创业服务',url=f'https://example.gov.cn/{i}') for i in range(extra)]
        self.assertEqual(core.scan(self.topic,many)['count'],core.SEARCH_COUNT)
    def test_url_dedup_preserves_user_notes(self):
        core.scan(self.topic,lambda q:[self.result()])
        p=core.state()['projects'][0]
        core.update_project(dict(id=p['id'],favorite=True,notes='重点跟进'))
        core.scan(self.topic,lambda q:[self.result()])
        state=core.state()
        self.assertEqual(len(state['projects']),1)
        self.assertEqual(state['projects'][0]['notes'],'重点跟进')
        self.assertEqual(state['projects'][0]['favorite'],1)
        self.assertEqual(len(state['matches']),1)
    def test_partial_failure_is_visible(self):
        def provider(q):
            if q.startswith('一人公司'):raise RuntimeError('限流')
            return [self.result()]
        r=core.scan(self.topic,provider)
        self.assertEqual(r['status'],'部分成功')
        self.assertEqual(r['count'],1)
        self.assertEqual(len(r['errors']),1)
    def test_failure_retains_results(self):
        core.scan(self.topic,lambda q:[self.result()])
        def fail(q):raise RuntimeError('失败')
        self.assertEqual(core.scan(self.topic,fail)['status'],'失败')
        self.assertEqual(len(core.state()['projects']),1)
    def test_topic_edit_rematches(self):
        core.scan(self.topic,lambda q:[self.result()])
        core.save_topic(dict(id=self.topic,name='新主题',keywords='医疗设备',excludes='',region=''))
        self.assertFalse(core.state()['matches'])
    def test_invalid_url_rejected(self):
        self.assertIsNone(core.valid_url('javascript:alert(1)'))
        self.assertIsNone(core.valid_url('https://user:pass@example.com'))
        self.assertEqual(core.valid_url('https://example.com/a?utm_source=x&id=3#section'),'https://example.com/a?id=3')
    def test_deepseek_request_and_response_contract(self):
        response={'results':[{'title':'招标','url':'https://example.com','content':'','summary':'正文摘要','snippet':'短摘','date':'2026-10-31T00:00:00Z'}]}
        with patch('urllib.request.urlopen',return_value=io.BytesIO(json.dumps(response).encode())) as m:
            r=core.deepseek_search('OPC'*100,'test-secret',True)
            request=m.call_args.args[0]
            self.assertEqual(request.full_url,'https://api.deepseek.com/v1/tools/search')
            body=json.loads(request.data)
            self.assertLessEqual(len(body['query']),128)
            self.assertTrue(body['need_content'])
            self.assertEqual(r[0]['content'],'正文摘要')
            self.assertEqual(r[0]['published_date'],response['results'][0]['date'])
    def test_missing_key(self):
        with patch.dict(os.environ,{'DEEPSEEK_API_KEY':''}):
            with self.assertRaisesRegex(ValueError,'DEEPSEEK_API_KEY'):core.scan(self.topic)
    def test_csv_formula_escape_and_deadline_validation(self):
        core.scan(self.topic,lambda q:[self.result()])
        p=core.state()['projects'][0]
        core.update_project({'id':p['id'],'notes':'=CMD()'})
        self.assertIn("'=CMD()",core.export_csv())
        with self.assertRaises(ValueError):core.update_project({'id':p['id'],'deadline':'tomorrow'})
    def test_seed_idempotent(self):
        core.seed(); core.seed()
        self.assertEqual(len(core.state()['projects']),4)
        self.assertTrue(all(p['demo'] for p in core.state()['projects']))
    def test_fetch_failure_keeps_old_content(self):
        core.scan(self.topic,lambda q:[self.result()])
        p=core.state()['projects'][0]
        with patch.dict(os.environ,{'DEEPSEEK_API_KEY':'test-key'}),patch('urllib.request.urlopen',return_value=io.BytesIO(b'{"content":""}')):
            with self.assertRaises(ValueError):core.fetch_page(p['id'])
        self.assertEqual(core.state()['projects'][0]['content'],p['content'])

    # ---------- 自动工作流 ----------
    def test_workflow_validation(self):
        with self.assertRaises(ValueError):core.save_workflow(dict(name='x'*61))
        with self.assertRaises(ValueError):core.save_workflow(dict(name='ok',freq='hourly'))
        with self.assertRaises(ValueError):core.save_workflow(dict(name='ok',at_time='25:00'))
        with self.assertRaises(ValueError):core.save_workflow(dict(name='ok',freq='interval',interval_hours=5))
        with self.assertRaises(ValueError):core.save_workflow(dict(name='ok',topic_ids=[99999]))
        wid=core.save_workflow(dict(name='ok',at_time='9:5',topic_ids=[self.topic]))
        wf=core.workflows()[0]
        self.assertEqual(wf['at_time'],'09:05')
        self.assertEqual(wf['topic_ids'],str(self.topic))
        self.assertEqual(wf['id'],wid)
    def test_workflow_partial_update_keeps_other_fields(self):
        # 只改开关时不能把名称、参与主题覆盖成默认值（旧页面缓存会把它写回）
        wid=core.save_workflow(dict(name='每日巡检',enabled=1,freq='interval',interval_hours=6,topic_ids=[self.topic]))
        core.save_workflow(dict(id=wid,enabled=0))
        wf=core.workflows()[0]
        self.assertEqual(wf['enabled'],0)
        self.assertEqual(wf['name'],'每日巡检')
        self.assertEqual(wf['freq'],'interval')
        self.assertEqual(wf['interval_hours'],6)
        self.assertEqual(wf['topic_ids'],str(self.topic))
    def test_workflow_new_record_does_not_trigger_immediately(self):
        # 新建时若今天的时点已过，不应立刻补跑一整轮
        core.save_workflow(dict(name='每日',enabled=1,freq='daily',at_time='00:01'))
        self.assertTrue(core.workflows()[0]['last_run_at'])
        self.assertFalse(core.workflow_due(core.workflows()[0]))
    def test_workflow_due_daily_and_interval(self):
        wf=dict(enabled=1,freq='daily',at_time='09:00',last_run_at='')
        self.assertFalse(core.workflow_due(wf,core.datetime(2026,11,3,8,59)))
        self.assertTrue(core.workflow_due(wf,core.datetime(2026,11,3,9,0)))
        # 今天 09:00 已跑过 → 今天不再触发
        wf['last_run_at']='2026-11-03T02:00:00+00:00'
        self.assertFalse(core.workflow_due(wf,core.datetime(2026,11,3,15,0)))
        # 停用后永不触发
        wf['enabled']=0
        self.assertFalse(core.workflow_due(wf,core.datetime(2026,11,4,10,0)))
        # 间隔模式：满 6 小时才触发
        iv=dict(enabled=1,freq='interval',interval_hours=6,last_run_at=core.now())
        self.assertFalse(core.workflow_due(iv))
    def test_run_workflow_summary_and_export(self):
        other=core.save_topic(dict(name='夜校',keywords='青年夜校',excludes='',region=''))
        wid=core.save_workflow(dict(name='巡检',enabled=1,topic_ids=[self.topic,other]))
        def provider(q):
            return [dict(title='OPC社区运营招标公告',content='江苏盐城采购OPC创业服务',url='https://a.gov.cn/1')]
        r=core.run_workflow(wid,'manual',provider=provider)
        self.assertEqual((r['topics_total'],r['topics_ok']),(2,2))
        self.assertEqual(r['new'],1)                 # 两个主题命中同一条，新增只算一次
        self.assertEqual(len(r['detail']),2)
        self.assertEqual(core.workflows()[0]['last_run_at']!='',True)
        csv=core.export_csv(run_id=r['id'])
        self.assertEqual(len(csv.strip().splitlines()),2)   # 表头 + 1 条
        # 第二轮没有新网址，新增为 0，导出只有表头
        r2=core.run_workflow(wid,'manual',provider=provider)
        self.assertEqual(r2['new'],0)
        self.assertEqual(len(core.export_csv(run_id=r2['id']).strip().splitlines()),1)
        with self.assertRaises(ValueError):core.export_csv(run_id=999999)
    def test_run_workflow_reports_failed_topic(self):
        wid=core.save_workflow(dict(name='巡检',topic_ids=[self.topic]))
        def boom(q): raise RuntimeError('搜索失败')
        r=core.run_workflow(wid,'manual',provider=boom)
        self.assertEqual(r['status'],'失败')
        self.assertEqual(r['detail'][0]['status'],'失败')
        self.assertIn('搜索失败',r['detail'][0]['errors'])

    # ---------- 定向采集 ----------
    def test_crawler_parsers(self):
        ewb=('<li><span class="ewb-date r"> 2026-11-02</span>'
             '<a href="/doc/2026/11/02/1.shtml" title="某单位OPC平台采购公告">某单位OPC平台采购公告</a></li>')
        items=crawler.parse_ewb(ewb,'https://ggzy.example.gov.cn/')
        self.assertEqual(items[0]['title'],'某单位OPC平台采购公告')
        self.assertEqual(items[0]['published'],'2026-11-02')
        self.assertEqual(items[0]['url'],'https://ggzy.example.gov.cn/doc/2026/11/02/1.shtml')
        # link 模式按 URL 正则挑条目，导航链接必须被排除
        page=('<div>2026-10-31 <a href="/jiangsu/js_cggg/details.html?ggid=abc">OPC社区运营服务采购公告</a></div>'
              '<a href="/jiangsu/cggg_search.html">公告检索</a>')
        got=crawler.parse_links(page,'http://www.ccgp-jiangsu.gov.cn/',r'/js_cggg/details\.html')
        self.assertEqual(len(got),1)
        self.assertEqual(got[0]['published'],'2026-10-31')
        self.assertTrue(got[0]['url'].startswith('http://www.ccgp-jiangsu.gov.cn/jiangsu/'))
    def test_crawler_detail_extracts_text_and_date(self):
        page=('<html><body><div class="nav">首页 公告检索</div>'
              '<div class="content">发布时间：2026-11-01<br>本项目采购OPC社区运营服务，预算100万元。</div></body></html>')
        detail=crawler.parse_detail(page)
        self.assertIn('OPC社区运营服务',detail['content'])
        self.assertNotIn('<div',detail['content'])
        self.assertEqual(detail['published'],'2026-11-01')
        self.assertEqual(crawler.parse_detail('<p>无日期</p>')['published'],'')
    def test_collect_isolates_failed_source(self):
        ok=dict(id='a',name='可用源',mode='link',list=['https://a.example/list'],base='https://a.example/',url_re=r'/notice/')
        bad=dict(id='b',name='失效源',mode='link',list=['https://b.example/list'],base='https://b.example/',url_re=r'/notice/')
        def fake(url):
            if 'b.example' in url: raise urllib.error.URLError('down')
            return '<a href="/notice/1.html">某单位OPC平台采购公告</a>'
        with patch.object(crawler,'SOURCES',[ok,bad]):
            r=crawler.collect(fetcher=fake,pause=0)
        self.assertEqual(len(r['items']),1)
        self.assertEqual({s['id']:bool(s['error']) for s in r['sources']},{'a':False,'b':True})
    def test_crawl_marks_origin_as_crawl(self):
        def collector(known=None,keywords=None):
            return {'items':[{'source':'测试源','url':'https://a.gov.cn/1','title':'OPC社区运营招标公告',
                              'content':'江苏盐城采购OPC创业服务','published':'2026-11-02'}],
                    'sources':[{'id':'t','name':'测试源','listed':1,'fetched':1,'error':''}]}
        r=core.crawl_topic(self.topic,collector)
        self.assertEqual((r['status'],r['count'],r['new']),('成功',1,1))
        p=core.state()['projects'][0]
        self.assertEqual(p['origin'],'crawl')
        # 采集器给的是 published，搜索给的是 published_date，两个字段都要认
        self.assertEqual(p['published'],'2026-11-02')
        self.assertEqual(core.state()['runs'][0]['kind'],'crawl')
    def test_crawl_keeps_first_origin(self):
        core.scan(self.topic,lambda q:[self.result()])
        def collector(known=None,keywords=None):
            return {'items':[{'source':'x','url':'https://example.gov.cn/a?x=1',
                              'title':'OPC社区运营招标公告','content':'江苏盐城采购OPC创业服务'}],
                    'sources':[{'id':'t','name':'x','listed':1,'fetched':0,'error':''}]}
        self.assertEqual(core.crawl_topic(self.topic,collector)['new'],0)
        self.assertEqual(core.state()['projects'][0]['origin'],'search')
    def test_crawl_source_failure_is_visible(self):
        def collector(known=None,keywords=None):
            return {'items':[],'sources':[{'id':'a','name':'某站','listed':0,'fetched':0,'error':'站点无法访问'}]}
        r=core.crawl_topic(self.topic,collector)
        self.assertEqual(r['status'],'失败')
        self.assertEqual(r['errors'][0]['message'],'站点无法访问')
    def test_demo_data_marked_as_demo_origin(self):
        core.seed()
        self.assertTrue(all(p['origin']=='demo' for p in core.state()['projects']))
    def test_csv_reports_origin_column(self):
        core.scan(self.topic,lambda q:[self.result()])
        csv_text=core.export_csv()
        self.assertIn('来源渠道',csv_text.splitlines()[0])
        self.assertIn('DeepSeek 搜索',csv_text)

if __name__=='__main__':unittest.main()
