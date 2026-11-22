from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core


class NoticeEvidenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.addCleanup(setattr, core, 'DB', core.DB)
        core.DB = Path(temp.name) / 'evidence.db'
        core.init()
        self.topic = dict(name='企业服务', keywords='企业服务', excludes='', region='')
        self.topic['id'] = core.save_topic(self.topic)
        self.item = dict(url='https://www.ccgp.gov.cn/notice',
                         title='企业服务采购公告', content='服务范围：运营孵化中心。')

    def save(self, item, origin='search', topic=None):
        with core.connect() as db:
            return core.save_result(db, topic or self.topic, item, origin)

    def test_title_only_revisit_preserves_evidence_and_matches_other_topic(self):
        original = {**self.item, 'published': '2026-11-03'}
        pid, _ = self.save(original)
        before = core.state()['projects'][0]
        core.update_project(dict(id=pid, favorite=True, notes='已核对原文'))
        with core.connect() as db:
            tid = db.execute("INSERT INTO topics(name,keywords,excludes,region,created_at) VALUES(?,?,?,?,?)",
                             ('孵化中心', '孵化中心', '', '', core.now())).lastrowid
        other = dict(id=tid, name='孵化中心', keywords='孵化中心', excludes='', region='')
        listing = {**self.item, 'content': self.item['title'], 'published': ''}

        self.assertEqual(self.save(listing, 'crawl', other), (pid, False))
        state = core.state()
        updated = state['projects'][0]
        self.assertEqual(updated['content'], before['content'])
        self.assertEqual(updated['published'], before['published'])
        self.assertEqual(updated['first_seen_at'], before['first_seen_at'])
        self.assertGreaterEqual(updated['last_seen_at'], before['last_seen_at'])
        self.assertEqual(updated['notes'], '已核对原文')
        self.assertEqual(updated['favorite'], 1)
        self.assertEqual(updated['origin'], 'search')
        self.assertEqual({m['topic_id'] for m in state['matches']}, {self.topic['id'], tid})

    def test_deadline_needs_official_detail_and_survives_weaker_search(self):
        body = '获取采购文件截止时间：2026年11月9日。提交投标文件截止时间、开标时间和地点：2026年11月12日 09:30（北京时间）。'
        row = {**self.item, 'content': body, 'published': '2026-11-03'}
        pid, _ = self.save(row)
        self.assertEqual(core.state()['projects'][0]['official_deadline'], '')

        self.save({**row, 'content_kind': 'official_detail'}, 'crawl')
        core.update_project(dict(id=pid, deadline='2026-11-10T08:30'))
        self.save({**row, 'content': '投标截止时间：2026年11月13日', 'published': '2026-11-04'})
        result = core.state()['projects'][0]
        self.assertEqual(result['notice_kind'], 'opportunity')
        self.assertEqual(result['content'], body)
        self.assertEqual(result['official_deadline'], '2026-11-12')
        self.assertEqual(result['official_deadline_at'], '2026-11-12T09:30+08:00')
        self.assertIn('提交投标文件截止时间', result['deadline_evidence'])
        self.assertEqual(result['published'], '2026-11-03')
        self.assertEqual(result['deadline'], '2026-11-10T08:30')

    def test_distinguishes_purchase_opportunity_from_lifecycle_notices(self):
        titles = {
            '排污分流标段变更登记服务项目采购公告': 'opportunity',
            '企业服务招标公告-采购与招标网': 'opportunity',
            '企业服务采购公告（更正）': 'correction',
            '企业服务中标候选人公示': 'result',
            '企业服务采购意向': 'intent',
            '企业服务采购终止公告': 'terminated',
            '企业服务采购合同公告': 'contract',
        }
        for title, expected in titles.items():
            with self.subTest(title=title):
                self.assertEqual(core.notice_kind(title, 1), expected)

    def test_deadline_skips_conflicting_and_non_submission_dates(self):
        for content in ('获取采购文件截止时间：2026年11月9日',
                        '响应文件提交截止时间：2026年11月31日',
                        '投标截止时间：2026年11月9日 9:00；投标截止时间：2026年11月9日 10:00'):
            with self.subTest(content=content):
                self.assertEqual(core.extract_deadline(content), ('', '', ''))

    def test_opc_business_context_affects_matching_without_breaking_industrial_topic(self):
        business = dict(name='OPC 创业社区', keywords='OPC', excludes='', region='')
        industrial = {**business, 'name': '工业通信'}
        title = 'OPC UA 工业通信设备采购公告'
        self.assertLess(core.evaluate(business, title, '')[0], core.evaluate(industrial, title, '')[0])
        self.assertIsNotNone(core.evaluate(business, '单人公司孵化采购公告', ''))
        self.assertIsNone(core.evaluate(industrial, '单人公司孵化采购公告', ''))
        self.assertIsNone(core.evaluate(business, 'OPCU 设备采购公告', ''))


if __name__ == '__main__':
    unittest.main()
