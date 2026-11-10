from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core


class BuyerTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.addCleanup(setattr, core, 'DB', core.DB)
        core.DB = Path(temp.name) / 'buyers.db'
        core.init()
        self.topic = dict(name='企业服务', keywords='企业服务', excludes='', region='')
        self.topic['id'] = core.save_topic(self.topic)
        self.item = dict(url='https://www.ccgp.gov.cn/notice', title='企业服务采购公告')

    def save(self, item, origin='crawl'):
        with core.connect() as db:
            return core.save_result(db, self.topic, {**self.item, **item}, origin)[0]

    def test_follow_normalized_name_and_unfollow_preserve_association(self):
        bid = core.save_buyer({'name': '  ＡＢ 企业服务中心  '})
        self.assertEqual(core.save_buyer({'name': 'ab 企业服务中心'}), bid)
        pid = self.save({'content': self.item['title']})
        core.update_project({'id': pid, 'buyer_name': 'AB 企业服务中心', 'notes': '已核对'})
        core.save_buyer({'id': bid, 'followed': False})

        state = core.state()
        buyer, project = state['buyers'][0], state['projects'][0]
        self.assertEqual(len(state['buyers']), 1)
        self.assertFalse(buyer['followed'])
        self.assertEqual(buyer['project_count'], 1)
        self.assertEqual(project['buyer_id'], bid)
        self.assertEqual(project['buyer_source'], 'manual')
        self.assertEqual(project['notes'], '已核对')
        self.assertEqual(core.buyer_projects(bid)['projects'][0]['id'], pid)

    def test_official_body_beats_listing_and_respects_manual_clear(self):
        listed = {'buyer_name': '名单单位', 'buyer_source': 'official_listing',
                  'content': self.item['title']}
        pid = self.save(listed)
        self.assertEqual(core.state()['projects'][0]['buyer_source'], 'official_listing')
        body = ('1.采购人信息\n单位名称：徐州市财政效能中心\n地 址：某地\n'
                '2.采购代理机构信息\n名 称：某采购服务机构')
        detail = {'content': body, 'content_kind': 'official_detail'}
        self.save(detail)
        self.save(listed)
        project = core.state()['projects'][0]
        self.assertEqual(project['buyer_source'], 'official')
        self.assertEqual(project['buyer_name'], '徐州市财政效能中心')

        core.update_project({'id': pid, 'buyer_name': ''})
        self.save({**listed, **detail, 'content': body + '\n补充内容'})
        project = core.state()['projects'][0]
        self.assertIsNone(project['buyer_id'])
        self.assertEqual(project['buyer_name'], '')
        self.assertEqual(project['buyer_source'], 'manual')

    def test_inference_requires_official_source_and_unambiguous_fields(self):
        body = '1.采购人信息\n名 称：某采购单位'
        self.save({'content': body, 'content_kind': 'official_detail', 'buyer_name': '测试采购人',
                   'buyer_source': 'official_listing'}, origin='search')
        self.save({'url': 'https://example.com/notice', 'content': body, 'content_kind': 'official_detail',
                   'buyer_name': '测试采购人', 'buyer_source': 'official_listing'})
        self.assertFalse(core.state()['buyers'])
        self.assertEqual(core.extract_buyer('2.采购代理机构信息\n名 称：某采购代理机构'), '')
        self.assertEqual(core.extract_buyer(body + '\n招标人：另一采购单位'), '')
        self.assertEqual(core.extract_buyer('招 标 人：某采购单位 联系人：某某'), '某采购单位')

    def test_paged_buyer_list_reaches_rows_beyond_state_cap(self):
        bid = core.save_buyer({'name': '测试采购人'})
        stamp = core.now()
        with core.connect() as db:
            db.executemany('''INSERT INTO projects(url,title,content,source,fetched_at,buyer_id)
                              VALUES(?,?,?,?,?,?)''',
                           [(f'https://example.com/{i}', '公告', '', 'example.com', stamp,
                             bid if i < 52 else None) for i in range(2052)])
        self.assertEqual(len(core.state()['projects']), 2000)
        self.assertTrue(all(p['buyer_id'] is None for p in core.state()['projects']))

        first = core.buyer_projects(bid)
        second = core.buyer_projects(bid, first['next_before'])
        self.assertEqual(len(first['projects']), 50)
        self.assertEqual([p['id'] for p in second['projects']], [2, 1])
        self.assertIsNone(second['next_before'])


if __name__ == '__main__':
    unittest.main()
