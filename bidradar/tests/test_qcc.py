import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core
import qcc


def record(url='https://example.gov.cn/notice/1', title='运营服务招标公告'):
    return {'Id':'vendor-1', 'Title':title, 'ContentUrl':url, 'ProjectNo':'X-1',
            'Province':'江苏省', 'City':'南京市', 'BudgetAmt':'30万元',
            'PublishDate':'2026-11-01', 'BidEndDate':'2026-12-01 10:00',
            'BidInviUnitList':[{'Name':'测试采购人', 'TelNo':'should-not-be-imported'}],
            'BidProgressList':['招标公告']}


class QccApiTest(unittest.TestCase):
    def test_signed_request_uses_documented_single_page(self):
        payload = {'Status':'200', 'Paging':{'TotalRecords':50},
                   'Result':{'VerifyResult':1, 'Data':[record()]}}
        with patch('qcc.time.time', return_value=1700000000), \
                patch('qcc._open', return_value=io.BytesIO(json.dumps(payload).encode())) as send:
            result = qcc.search('运营 服务', 'test-app-key', 'test-secret',
                                area_code='320100', msg_type='3', page_index=2)
        self.assertEqual(result['total'], 50)
        self.assertEqual(len(result['items']), 1)
        self.assertEqual(send.call_count, 1)
        request = send.call_args.args[0]
        self.assertEqual(urlsplit(request.full_url).hostname, 'api.qichacha.com')
        self.assertEqual(parse_qs(urlsplit(request.full_url).query)['pageSize'], ['20'])
        self.assertEqual(parse_qs(urlsplit(request.full_url).query)['areaCode'], ['320100'])
        expected = hashlib.md5(b'test-app-key1700000000test-secret').hexdigest().upper()
        self.assertEqual(request.get_header('Token'), expected)
        self.assertEqual(request.get_header('Timespan'), '1700000000')

    def test_invalid_input_never_sends_request(self):
        with patch('qcc._open') as send:
            with self.assertRaises(ValueError):
                qcc.search('运营', 'key', 'secret', area_code='南京市')
            with self.assertRaises(ValueError):
                qcc.search('运营', '', '')
            send.assert_not_called()

    def test_error_does_not_expose_keys_or_provider_message(self):
        payload = {'Status':'115', 'Message':'test-secret test-app-key'}
        with patch('qcc._open', return_value=io.BytesIO(json.dumps(payload).encode())):
            with self.assertRaises(ValueError) as error:
                qcc.search('运营', 'test-app-key', 'test-secret')
        self.assertNotIn('test-secret', str(error.exception))
        self.assertNotIn('test-app-key', str(error.exception))
        self.assertIn('115', str(error.exception))

    def test_empty_result_is_not_a_failure(self):
        payload = {'Status':'200', 'Result':{'VerifyResult':0, 'Data':None}}
        with patch('qcc._open', return_value=io.BytesIO(json.dumps(payload).encode())):
            self.assertEqual(qcc.search('运营', 'key', 'secret')['items'], [])


class QccImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_db = core.DB
        core.DB = Path(self.temp.name) / 'test.db'
        core.init()
        self.topic_id = core.save_topic({'name':'运营', 'keywords':'运营', 'region':'江苏'})

    def tearDown(self):
        core.DB = self.old_db
        self.temp.cleanup()

    def test_import_deduplicates_and_preserves_user_followup(self):
        calls = []
        def provider(*args, **kwargs):
            calls.append((args, kwargs))
            return {'items':[record(), record('', '运营采购公告')], 'total':2, 'page':1}
        result = core.qcc_search(self.topic_id, '运营', provider=provider)
        self.assertEqual((result['new'], result['count'], result['missing_url']), (1, 1, 1))
        project = core.state()['projects'][0]
        self.assertEqual(project['origin'], 'qcc')
        self.assertEqual(project['qcc_budget'], '30万元')
        self.assertEqual(project['qcc_buyer'], '测试采购人')
        self.assertNotIn('should-not-be-imported', project['content'])
        core.update_project({'id':project['id'], 'favorite':True, 'notes':'人工记录'})
        again = core.qcc_search(self.topic_id, '运营', provider=provider)
        self.assertEqual((again['new'], again['count']), (0, 1))
        project = core.state()['projects'][0]
        self.assertEqual((project['favorite'], project['notes']), (1, '人工记录'))
        self.assertEqual(len(calls), 2)

    def test_existing_source_keeps_origin_and_adds_qcc_evidence(self):
        core.scan(self.topic_id, lambda query: [{'title':'运营服务招标公告',
                  'content':'江苏采购运营服务', 'url':'https://example.gov.cn/notice/1'}])
        result = core.qcc_search(self.topic_id, '运营', provider=lambda *a, **k:
                                 {'items':[record()], 'total':1, 'page':1})
        self.assertEqual(result['new'], 0)
        project = core.state()['projects'][0]
        self.assertEqual(project['origin'], 'search')
        self.assertEqual(project['qcc_available'], 1)
        self.assertEqual(project['qcc_project_no'], 'X-1')


if __name__ == '__main__':
    unittest.main()
