import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import core


class QueryPlanTest(unittest.TestCase):
    def test_plan_rotates_regions_and_extra_terms_within_budget(self):
        topic = {'keywords': '活动运营,志愿服务,活动执行,活动运营', 'region': '全国,无锡,苏州,苏州'}
        base = ['活动运营 招标 采购', '志愿服务 招标 采购']
        self.assertEqual(core.queries(topic, 0), base + ['活动运营 招标 采购 无锡'])
        self.assertEqual(core.queries(topic, 1), base + ['活动运营 招标 采购 苏州'])
        self.assertEqual(core.queries(topic, 2), base + ['活动执行 招标 采购'])
        self.assertEqual(core.queries(topic, 3), core.queries(topic, 0))

    def test_opc_expansion_requires_business_context(self):
        business = {'name': '创业社区', 'keywords': 'OPC,opc', 'region': ''}
        industrial = {'name': '工业通信', 'keywords': 'OPC', 'region': ''}
        self.assertEqual(core.topic_keywords(business), ['OPC', '一人公司', '单人公司'])
        self.assertEqual(core.queries(industrial), ['OPC 招标 采购'])


class SearchQueryCacheTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        database = patch.object(core, 'DB', Path(folder.name) / 'test.db')
        database.start()
        self.addCleanup(database.stop)
        core.init()
        self.first = core.save_topic({'name': '主题一', 'keywords': '活动运营'})
        self.second = core.save_topic({'name': '主题二', 'keywords': '活动运营'})

    def test_raw_candidates_are_cached_and_matched_for_each_topic(self):
        provider = Mock(return_value=[{
            'url': 'https://example.gov.cn/a',
            'title': '活动运营采购公告',
            'content': '活动运营服务公开采购',
        }])
        cache = {}
        first = core.scan(self.first, provider, query_cache=cache)
        second = core.scan(self.second, provider, query_cache=cache)
        provider.assert_called_once_with('活动运营 招标 采购')
        self.assertEqual(first['found_ids'], second['found_ids'])
        self.assertEqual((first['new'], second['new']), (1, 0))
        self.assertEqual(len(core.state()['matches']), 2)

    def test_failed_request_can_retry_but_successful_empty_response_is_cached(self):
        provider = Mock(side_effect=[RuntimeError('网络异常'), []])
        cache = {}
        self.assertEqual(core.scan(self.first, provider, query_cache=cache)['status'], '失败')
        self.assertEqual(core.scan(self.second, provider, query_cache=cache)['status'], '成功')
        self.assertEqual(core.scan(self.first, provider, query_cache=cache)['status'], '成功')
        self.assertEqual(provider.call_count, 2)

    def test_cache_is_separate_for_each_credential_and_search_mode(self):
        cache = {}
        with patch.object(core, 'deepseek_search', return_value=[]) as search:
            with patch.dict(os.environ, {'DEEPSEEK_API_KEY': 'first-key'}):
                with patch.object(core, 'config', return_value={'official_only': False}):
                    core.scan(self.first, query_cache=cache)
                with patch.object(core, 'config', return_value={'official_only': True}):
                    core.scan(self.first, query_cache=cache)
            with patch.dict(os.environ, {'DEEPSEEK_API_KEY': 'second-key'}):
                with patch.object(core, 'config', return_value={'official_only': False}):
                    core.scan(self.first, query_cache=cache)
        self.assertEqual(search.call_count, 3)


if __name__ == '__main__':
    unittest.main()
