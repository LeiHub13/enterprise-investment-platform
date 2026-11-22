from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import crawler


SOURCE = next(source for source in crawler.SOURCES if source['id'] == 'xuzhou-intent')
TITLE = '徐州市示例单位2026年11月政府采购意向公告'
PATH = '/zfcg/jiangsu/js_cggg/details.html?gglb=cgyx&amp;ggid=abc123'
ROW = f'''
<tr>
    <td class="td-cgdw" tip-msg="徐州市示例单位">徐州市示例单位</td>
    <td class="td-xmmc" tip-msg="{TITLE}">
        <span class='cggg-table-cgfs'></span>
        <a href="{PATH}" target="_blank">{TITLE}</a>
    </td>
    <td class='cggg-time'>2026-11-03</td>
</tr>
'''


class IntentSourceTest(unittest.TestCase):
    def test_reads_explicit_listing_fields(self):
        [item] = crawler.parse_xuzhou_intents(ROW, SOURCE['base'])
        self.assertEqual(item, {
            'url': 'https://czj.xz.gov.cn/zfcg/jiangsu/js_cggg/details.html?gglb=cgyx&ggid=abc123',
            'title': TITLE,
            'published': '2026-11-03',
            'notice_kind': 'intent',
            'buyer_name': '徐州市示例单位',
            'buyer_source': 'official_listing',
        })

    def test_skips_other_notice_types_and_external_links(self):
        other_notice = ROW.replace('gglb=cgyx', 'gglb=gkzb')
        external = ROW.replace(PATH, 'https://example.test' + PATH)
        missing_id = ROW.replace('&amp;ggid=abc123', '')
        self.assertEqual(crawler.parse_xuzhou_intents(other_notice + external + missing_id, SOURCE['base']), [])

    def test_collect_preserves_listing_evidence_without_fetching_empty_template(self):
        with patch.object(crawler, 'SOURCES', [SOURCE]):
            with patch.object(crawler, 'fetch', return_value=ROW + ROW) as fetch:
                result = crawler.collect(pause=0)
        fetch.assert_called_once_with(SOURCE['list'][0])
        [item] = result['items']
        self.assertEqual(item['content'], TITLE)
        self.assertEqual(item['buyer_name'], '徐州市示例单位')
        self.assertEqual(item['notice_kind'], 'intent')
        self.assertEqual(result['sources'][0]['listed'], 1)
        self.assertEqual(result['sources'][0]['fetched'], 0)
        self.assertEqual(result['sources'][0]['latest_published'], '2026-11-03')


if __name__ == '__main__':
    unittest.main()
