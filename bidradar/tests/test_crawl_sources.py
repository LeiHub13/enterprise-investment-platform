import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crawler


WUXI = next(source for source in crawler.SOURCES if source['id'] == 'wuxi-ggzy')
DETAIL_ONE = 'http://ggzyjy.wuxi.gov.cn/doc/2026/11/04/4853046.shtml'
DETAIL_TWO = 'https://ggzyjy.wuxi.gov.cn/doc/2026/11/03/4852770.shtml'
PAYLOAD = json.dumps({
    'status': 0,
    'data': {'data': [
        {'writeTime': '2026-11-04',
         'title': '江阴市第三人民医院数据库集群采购项目采购公告',
         'url': DETAIL_ONE},
        {'writeTime': '2026-11-03',
         'title': '协作机器人自动化上下料项目采购公告',
         'url': '/doc/2026/11/03/4852770.shtml'},
        {'writeTime': '2026-11-03', 'title': '短', 'url': '/doc/x.html'},
        {'writeTime': '2026-11-03', 'title': '没有链接的采购公告', 'url': ''},
    ]},
}, ensure_ascii=False)
DETAIL_PAGE = ('<div class="content">发布时间：2026-11-04<br>'
               '本项目公开采购数据库集群与运维服务，欢迎符合条件的供应商参加。</div>')


class WuxiApiSourceTest(unittest.TestCase):
    def test_parser_keeps_listing_fields_and_skips_incomplete_rows(self):
        items = crawler.parse_wuxi_api(PAYLOAD, WUXI['base'])
        self.assertEqual([item['url'] for item in items], [DETAIL_ONE, DETAIL_TWO])
        self.assertEqual([item['published'] for item in items], ['2026-11-04', '2026-11-03'])
        self.assertEqual(items[0]['title'], '江阴市第三人民医院数据库集群采购项目采购公告')

    def test_collect_posts_official_api_and_fetches_details(self):
        calls = []

        def api_fetcher(url, form, referer):
            calls.append((url, form, referer))
            return PAYLOAD

        def fetcher(url):
            return DETAIL_PAGE if url in (DETAIL_ONE, DETAIL_TWO) else ''

        with patch.object(crawler, 'SOURCES', [WUXI]), patch.object(crawler.time, 'sleep'):
            result = crawler.collect(fetcher=fetcher, api_fetcher=api_fetcher, pause=0)

        [url, form, referer] = calls[0]
        self.assertEqual(url, WUXI['api'])
        self.assertEqual(referer, WUXI['list'][0])
        self.assertEqual((form['pageIndex'], form['pageSize']), (1, 20))
        self.assertIn('53061', form['chanId'])
        self.assertEqual(len(calls), 1)
        source = result['sources'][0]
        self.assertEqual((source['listed'], source['fetched'], source['error']), (2, 2, ''))
        self.assertEqual(source['latest_published'], '2026-11-04')
        self.assertTrue(all(item['content_kind'] == 'official_detail' for item in result['items']))
        self.assertIn('数据库集群', result['items'][0]['content'])

    def test_broken_api_response_only_marks_this_source(self):
        with patch.object(crawler, 'SOURCES', [WUXI]), patch.object(crawler.time, 'sleep'):
            result = crawler.collect(api_fetcher=lambda *_: 'not json', pause=0)
        self.assertEqual(result['items'], [])
        self.assertEqual(result['sources'][0]['listed'], 0)
        self.assertEqual(result['sources'][0]['error'], '页面解析失败')


if __name__ == '__main__':
    unittest.main()
