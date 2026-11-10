import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core
import crawler


class CrawlWorkflowTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        for setting in (patch.object(core, 'DB', Path(tmp.name) / 'test.db'),
                        patch.dict(os.environ, {'DEEPSEEK_API_KEY': ''})):
            setting.start()
            self.addCleanup(setting.stop)
        core.init()
        self.topic = core.save_topic({'name': '社区', 'keywords': '社区运营'})
        self.other = core.save_topic({'name': '创业', 'keywords': '创业服务'})
        self.url = 'https://example.gov.cn/notice/1.html'
        self.source = {'id': 'a', 'name': '测试源', 'mode': 'link',
                       'list': ['https://example.gov.cn/list'], 'url_re': r'/notice/'}

    def test_shared_crawl_saves_cache_and_reuses_detail(self):
        wid = core.save_workflow({'channels': 'crawl', 'auto_export': True})
        pages = {
            self.source['list'][0]: '<a href="/notice/1.html">社区运营与创业服务采购公告</a>',
            self.url: '<div class="content">发布时间：2026-11-02<br>本项目公开采购社区运营、创业服务，提供常驻人员及场地运营。</div>',
        }
        with patch.object(crawler, 'SOURCES', [self.source]), patch.object(crawler.time, 'sleep'), \
             patch.object(crawler, 'fetch', side_effect=pages.__getitem__) as fetch:
            first = core.run_workflow(wid, 'schedule')
            second = core.run_workflow(wid, 'schedule')
        self.assertEqual([call.args[0] for call in fetch.call_args_list],
                         [self.source['list'][0], self.url, self.source['list'][0]])
        self.assertEqual((first['status'], first['topics_ok'], first['found'], first['new']), ('成功', 2, 1, 1))
        self.assertEqual([item['count'] for item in first['detail']], [1, 1])
        self.assertEqual((second['found'], second['new'], second['sources'][0]['cache_hits']), (1, 0, 1))
        state = core.state()
        self.assertEqual(state['projects'][0]['published'], '2026-11-02')
        self.assertEqual(json.loads(state['workflow_runs'][0]['sources']), second['sources'])
        self.assertIn('社区运营与创业服务采购公告', Path(first['exported']).read_text(encoding='utf-8'))

    def test_dual_workflow_keeps_crawl_when_search_key_missing(self):
        wid = core.save_workflow({'channels': 'both', 'topic_ids': [self.topic]})
        collector = Mock(return_value={
            'items': [{'title': '社区运营服务采购公告', 'url': self.url}],
            'sources': [{'id': 'a', 'name': '测试源', 'listed': 1, 'error': ''}],
        })
        result = core.run_workflow(wid, 'schedule', collector=collector)
        collector.assert_called_once()
        channels = result['detail'][0]['channels']
        self.assertEqual((result['status'], result['new']), ('部分成功', 1))
        self.assertEqual(channels['crawl']['status'], '成功')
        self.assertIn('DEEPSEEK_API_KEY', channels['search']['errors'][0])

    def test_dual_workflow_deduplicates_hits_from_both_channels(self):
        wid = core.save_workflow({'channels': 'both'})
        item = {'title': '社区运营与创业服务采购公告', 'url': self.url}
        result = core.run_workflow(wid, provider=lambda _: [item],
                                   collector=lambda **_: {'items': [item]})
        self.assertEqual((result['found'], result['new']), (1, 1))
        for row in result['detail']:
            self.assertEqual(row['count'], 1)
            self.assertEqual([channel['count'] for channel in row['channels'].values()], [1, 1])

    def test_source_failure_does_not_stop_another_source(self):
        bad = {**self.source, 'id': 'bad', 'list': ['https://bad.example/list']}

        def fetch(url):
            if url == bad['list'][0]:
                raise urllib.error.URLError('failed')
            if url == self.url:
                return '<p>投标截止时间：2026-11-15</p>'
            return '<a href="/notice/1.html">社区运营服务采购公告</a>'

        with patch.object(crawler, 'SOURCES', [bad, self.source]), patch.object(crawler.time, 'sleep'), \
             patch.object(crawler, 'fetch', side_effect=fetch):
            result = core.crawl_topic(self.topic)
        self.assertEqual((result['status'], result['count']), ('部分成功', 1))
        self.assertTrue(result['sources'][0]['error'])
        self.assertEqual(core.state()['projects'][0]['published'], '')

    def test_unread_detail_precedes_expired_detail_when_budget_is_limited(self):
        second_url = 'https://example.gov.cn/notice/2.html'
        title = '社区运营服务采购公告'
        page = (f'<a href="/notice/1.html">{title}</a>'
                '<a href="/notice/2.html">人才服务采购公告</a>')
        fetch = Mock(side_effect={self.source['list'][0]: page, second_url: '<p>正文</p>'}.__getitem__)
        cached = {self.url: {'title': title, 'content': '缓存详情', 'published': '',
                             'content_kind': 'crawl_excerpt', 'fresh': False}}
        with patch.object(crawler, 'SOURCES', [self.source]):
            result = crawler.collect(fetcher=fetch, keywords=['社区运营'], detail_limit=1,
                                     pause=0, lookup_details=lambda _: cached)
        self.assertEqual([call.args[0] for call in fetch.call_args_list], [self.source['list'][0], second_url])
        self.assertEqual(result['sources'][0]['cache_hits'], 1)

    def test_scheduler_runs_due_crawl_without_search_key(self):
        import app

        stop = Mock()
        stop.wait.side_effect = [False, True]
        stop.is_set.return_value = False
        workflows = [{'id': 1, 'channels': 'search'}, {'id': 2, 'channels': 'crawl'}]
        with patch.object(core, 'workflows', return_value=workflows), patch.object(core, 'workflow_due', return_value=True), \
             patch.object(core, 'run_workflow') as run:
            app.scheduler(stop)
        run.assert_called_once_with(2, 'schedule')


if __name__ == '__main__':
    unittest.main()
