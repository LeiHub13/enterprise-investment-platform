"""定向采集：绕开通用搜索引擎，直接读招标站点的公告栏目页。

和 core.deepseek_search 的分工：
- 搜索接口覆盖面宽，但每个查询最多 50 条、不能翻页，索引里省市交易中心的数据稀疏；
- 定向采集覆盖面窄，只读配置好的几个官方栏目，但能翻页、能拿到结构化日期，
  且新公告一发布就能看到，不依赖第三方索引的更新节奏。

设计要点：
- 声明式源配置：加站点只改 SOURCES，不用动逻辑。
- 按栏目模板解析公告链接；徐州采购意向列表还提供单独的采购单位和日期字段。
- 只读列表页和少量详情页，请求之间固定停顿，不绕验证码、不碰需要登录的页面。
- 任一源失败只影响该源，结果里如实返回，不伪造空数据。

只用标准库，和 core.py 保持一致。
"""
import html as htmllib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

UA = 'BidRadar/0.3 (local single-user tender digest)'
MAX_HTML = 2 * 1024 * 1024
DETAIL_TEXT = 12000

# 标题里出现这些词才算公告条目，用来挡掉导航、栏目名和页脚链接
NOTICE_WORDS = ('公告', '公示', '结果', '中标', '成交', '采购', '招标', '磋商',
                '谈判', '比选', '征集', '招募', '意向', '更正', '终止')

SOURCES = [
    {
        'id': 'jiangsu-ccgp',
        'name': '江苏政府采购网',
        'mode': 'link',
        'list': ['http://www.ccgp-jiangsu.gov.cn/'],
        'base': 'http://www.ccgp-jiangsu.gov.cn/',
        # 只认采购公告栏目。同站的 jgxx/jckh 是集中采购机构考核通报，属于内部公文不是线索。
        'url_re': r'/jiangsu/js_cggg/details\.html',
        # 该站详情页正文由前端脚本填充（<span id="content1"></span> 是空的），
        # 静态抓只能拿到空白合同模板，所以只取列表标题，不浪费请求去抓详情。
        'detail': False,
    },
    {
        'id': 'wuxi-ggzy',
        'name': '无锡市公共资源交易中心',
        # 2026-11 起采购公告栏目页由脚本渲染，静态 HTML 里没有列表；
        # 直接调用该页自己使用的官方列表接口，取第 1 页 20 条最新公告。
        'mode': 'wuxi_api',
        'list': ['https://ggzyjy.wuxi.gov.cn/wxsggzyjyzxzl/jyxx/zfcg/cggg/index.shtml'],
        'api': 'https://ggzyjy.wuxi.gov.cn/info_open/searchPublicResource',
        'chan': '53061,36908,36910,36911,36912,36913,36914,36918,53065,53069,64594',
        'page_size': 20,
        'base': 'https://ggzyjy.wuxi.gov.cn/',
    },
    {
        'id': 'xuzhou-intent',
        'name': '徐州政府采购网 · 采购意向',
        'mode': 'xuzhou_intent',
        'list': ['https://czj.xz.gov.cn/zfcg/'],
        'base': 'https://czj.xz.gov.cn/zfcg/',
        # 列表已提供采购单位及发布时间，详情正文由前端脚本填充。
        'detail': False,
    },
]

EWB_ITEM = re.compile(
    r'<span class="ewb-date[^"]*">\s*([0-9\-/\.]{8,12})\s*</span>\s*'
    r'<a[^>]*href="([^"]+)"[^>]*title="([^"]{4,200})"')
ANCHOR = re.compile(r'<a[^>]*href="([^"]{6,300})"[^>]*>(.*?)</a>', re.S)
TABLE_ROW = re.compile(r'<tr\b[^>]*>(.*?)</tr\s*>', re.S | re.I)
TABLE_CELL = re.compile(r'<td\b([^>]*)>(.*?)</td\s*>', re.S | re.I)
CLASS_ATTR = re.compile(r'(?:^|\s)class\s*=\s*([\'"])(.*?)\1', re.S | re.I)
ROW_LINK = re.compile(r'<a\b[^>]*?\shref\s*=\s*([\'"])(.*?)\1[^>]*>(.*?)</a\s*>', re.S | re.I)
DATE_RE = re.compile(r'(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})')
PUBLISHED_RE = re.compile(r'(?:发布日期|发布时间|公告日期|公告时间)\s*[:：]?\s*(' + DATE_RE.pattern + ')')
CONTENT_BLOCK = re.compile(
    r'<(div|td|article|section)[^>]*class="[^"]*(?:content|article|detail|main-text|zoom)[^"]*"[^>]*>(.*?)</\1>',
    re.S | re.I)


def _clean(text):
    """去掉标签与多余空白，返回单行文本。"""
    text = re.sub(r'(?s)<[^>]+>', ' ', text or '')
    text = htmllib.unescape(text)
    return re.sub(r'\s+', ' ', text).strip()


def _date(raw):
    """把 2026-11-03 / 2026年11月3日 统一成 ISO 日期，识别不了返回空串。"""
    m = DATE_RE.search(raw or '')
    if not m:
        return ''
    year, month, day = (int(x) for x in m.groups())
    if not (1 <= month <= 12 and 1 <= day <= 31):
        return ''
    return f'{year:04d}-{month:02d}-{day:02d}'


def _published(text):
    m = PUBLISHED_RE.search(text)
    return _date(m.group(1)) if m else ''


def fetch_post(url, form, referer='', timeout=20):
    """向站点自己的 JSON 接口发一次表单 POST（部分栏目页已改为脚本渲染）。"""
    request = urllib.request.Request(
        url,
        data=urllib.parse.urlencode(form).encode(),
        headers={
            'User-Agent': UA,
            'Accept': 'application/json',
            'Accept-Language': 'zh-CN,zh;q=0.9',
            'Referer': referer,
            'X-Requested-With': 'XMLHttpRequest',
        })
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(MAX_HTML)
        declared = response.headers.get_content_charset()
    return raw.decode(declared or 'utf-8', 'replace')


def parse_wuxi_api(page, base):
    """解析无锡交易中心列表接口的 JSON；字段为 title/url/writeTime。"""
    payload = json.loads(page)
    rows = (payload.get('data') or {}).get('data') or []
    items = []
    for row in rows:
        title = _clean(row.get('title') or '')
        href = str(row.get('url') or '').strip()
        if len(title) < 6 or not href:
            continue
        items.append({'url': urllib.parse.urljoin(base, href),
                      'title': title,
                      'published': _date(row.get('writeTime') or '')})
    return items


def fetch(url, timeout=20):
    """抓一个页面并解码。政务站 GBK/UTF-8 混用，按声明编码→UTF-8→GB18030 依次尝试。"""
    request = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'zh-CN,zh;q=0.9',
    })
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(MAX_HTML)
        declared = response.headers.get_content_charset()
    candidates = ([declared] if declared else []) + ['utf-8', 'gb18030']
    for encoding in candidates:
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode('utf-8', 'ignore')


def parse_ewb(page, base):
    """新点 EpointWebBuilder 模板：日期在 span 里，紧跟带 title 属性的链接。"""
    items = []
    for raw_date, href, title in EWB_ITEM.findall(page):
        items.append({'url': urllib.parse.urljoin(base, href),
                      'title': _clean(title),
                      'published': _date(raw_date)})
    return items


def parse_links(page, base, url_re):
    """通用模式：按 URL 正则挑出公告链接，标题取锚文本，日期取锚点附近的文字。"""
    pattern = re.compile(url_re)
    items, seen = [], set()
    for match in ANCHOR.finditer(page):
        href, inner = match.group(1), match.group(2)
        if not pattern.search(href):
            continue
        title = _clean(inner)
        if len(title) < 6:
            continue
        url = urllib.parse.urljoin(base, href)
        if url in seen:
            continue
        seen.add(url)
        # 只读取当前条目邻近的可见文字，避免把下一条公告的日期或链接参数当成本条日期。
        after = re.split(r'</(?:li|tr|div)>|<a\b', page[match.end():match.end() + 240], maxsplit=1, flags=re.I)[0]
        before = re.split(r'</(?:li|tr|div|a)>', page[max(0, match.start() - 80):match.start()], flags=re.I)[-1]
        published = ''
        for fragment in (after, before):
            text = _clean(fragment).strip(' []()【】（）')
            published = _date(text) if DATE_RE.fullmatch(text) else _published(text)
            if published:
                break
        items.append({'url': url, 'title': title, 'published': published})
    return items


def parse_xuzhou_intents(page, base):
    """读取徐州市财政局采购意向列表中的采购单位、原文链接和发布日期。"""
    items = []
    expected_host = urllib.parse.urlsplit(base).netloc
    for row in TABLE_ROW.findall(page):
        cells = {}
        for attrs, body in TABLE_CELL.findall(row):
            cls = CLASS_ATTR.search(attrs)
            if cls:
                cells.update(dict.fromkeys(cls[2].split(), body))
        link = ROW_LINK.search(cells.get('td-xmmc', ''))
        if not link:
            continue
        try:
            url = urllib.parse.urljoin(base, htmllib.unescape(link[2]))
            parts = urllib.parse.urlsplit(url)
        except ValueError:
            continue
        query = urllib.parse.parse_qs(parts.query)
        title = _clean(link[3])
        if (parts.netloc != expected_host or parts.scheme != 'https'
                or parts.path != '/zfcg/jiangsu/js_cggg/details.html'
                or query.get('gglb') != ['cgyx'] or not query.get('ggid') or not title):
            continue
        buyer = _clean(cells.get('td-cgdw', ''))
        items.append({'url': url, 'title': title, 'published': _date(_clean(cells.get('cggg-time', ''))),
                      'notice_kind': 'intent', 'buyer_name': buyer, 'buyer_source': 'official_listing' if buyer else ''})
    return items


def parse_detail(page):
    """从详情页取出正文与发布日期。正文优先取内容容器里最长的那块。"""
    text = ''
    kind = 'crawl_excerpt'
    blocks = [m.group(2) for m in CONTENT_BLOCK.finditer(page)]
    if blocks:
        best = max(blocks, key=len)
        candidate = _text_of(best)
        if len(candidate) >= 30:
            text = candidate
            kind = 'official_detail'
    if not text:
        text = _text_of(page)
    return {'content': '' if _is_template(text) else text[:DETAIL_TEXT],
            'content_kind': kind, 'published': _published(_text_of(page)[:2000])}


def _is_template(text):
    """空白表单模板：一串「字段名：」后面直接换行，真实内容是脚本填的。"""
    if not text:
        return False
    blanks = len(re.findall(r'：\s*(?=\n|$)', text))
    return blanks >= 4 and blanks * 20 >= len(text) * 0.5


def _text_of(fragment):
    fragment = re.sub(r'(?is)<(script|style|noscript)[^>]*>.*?</\1>', ' ', fragment)
    fragment = re.sub(r'(?i)<br\s*/?>|</p>|</div>|</li>|</tr>|</td>', '\n', fragment)
    fragment = re.sub(r'(?s)<[^>]+>', ' ', fragment)
    fragment = htmllib.unescape(fragment)
    fragment = re.sub(r'[ \t\r\f\v\u3000]+', ' ', fragment)
    return re.sub(r'\n\s*\n+', '\n', fragment).strip()


def collect(known=None, fetcher=None, keywords=None, per_source=30, detail_limit=6, pause=1.0,
            lookup_details=None, api_fetcher=None):
    """跑一遍所有源。

    known 是库里已有的 URL 集合：命中就不重复抓详情页，让每轮请求数可控。
    keywords 用于给详情抓取排序：标题就带关键词的条目优先占额度，
    否则额度会被一批不相关的公告占满，真正可能命中的反而没取到正文。
    lookup_details 批量读取当前栏目里的近期详情。每轮重扫有限列表，保留对迟到和更正公告的覆盖。
    fetcher / api_fetcher 可注入，便于测试时完全离线；后者用于脚本渲染栏目的 JSON 列表接口。
    """
    fetcher = fetcher or fetch
    api_fetcher = api_fetcher or fetch_post
    known = known or set()
    words = [str(w).strip().lower() for w in (keywords or []) if str(w).strip()]
    items, sources, details = [], [], {}

    for source in SOURCES:
        listed, error, fetched, cache_hits, latest_published = [], '', 0, 0, ''
        for list_url in source.get('list') or []:
            try:
                if source.get('mode') == 'wuxi_api':
                    page = api_fetcher(
                        source['api'],
                        {'chanId': source.get('chan', ''), 'jyly': '', 'pageIndex': 1,
                         'pageSize': source.get('page_size', 20)},
                        list_url)
                else:
                    page = fetcher(list_url)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError) as exc:
                error = _reason(exc)
                continue
            base = source.get('base') or list_url
            if source.get('mode') == 'wuxi_api':
                try:
                    parsed = parse_wuxi_api(page, base)
                except ValueError:
                    error = _reason(ValueError())
                    continue
            elif source.get('mode') == 'ewb':
                parsed = parse_ewb(page, base)
            elif source.get('mode') == 'xuzhou_intent':
                parsed = parse_xuzhou_intents(page, base)
            else:
                parsed = parse_links(page, base, source.get('url_re') or '.')
            listed.extend(parsed)
            if not parsed:
                error = error or '列表未解析到公告，请检查栏目是否为空或改版'
            if pause:
                time.sleep(pause)

        # 同一源的多页可能重叠，按 URL 去重并只留像公告的标题
        unique, seen = [], set()
        for item in listed:
            if item['url'] in seen:
                continue
            if not any(w in item['title'] for w in NOTICE_WORDS):
                continue
            seen.add(item['url'])
            unique.append(item)
        listed = unique[:per_source]
        cached = lookup_details([item['url'] for item in listed]) if lookup_details and source.get('detail', True) else {}
        # 优先未读或标题有更新的条目，避免固定间隔每次都刷新前几条而漏掉后续公告。
        listed.sort(key=lambda item: (cached.get(item['url'], {}).get('title') == item['title'],
                                     not any(w in item['title'].lower() for w in words)))
        for item in listed:
            url, title = item['url'], item['title']
            row = {**item, 'source': source['name'], 'published': item.get('published') or '', 'content': title}
            detail = details.get(url) or cached.get(url)
            if detail and detail['title'] != title:
                detail = None
            needs_detail = not detail or not detail.get('fresh', True)
            if needs_detail and source.get('detail', True) and url not in known and fetched < detail_limit:
                try:
                    parsed = parse_detail(fetcher(url))
                    detail = {'title': title, **parsed}
                    details[url] = detail
                except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError) as exc:
                    error = error or _reason(exc)
                fetched += 1
                if pause:
                    time.sleep(pause)
            if detail and url not in details:
                cache_hits += 1
            if detail:
                if len(detail['content']) > len(title):
                    row['content'] = detail['content']
                    row['content_kind'] = detail['content_kind']
                row['published'] = row['published'] or detail['published']
            latest_published = max(latest_published, row['published'])
            items.append(row)

        sources.append({'id': source['id'], 'name': source['name'],
                        'listed': len(listed), 'fetched': fetched, 'cache_hits': cache_hits,
                        'latest_published': latest_published, 'error': error})

    return {'items': items, 'sources': sources, 'details': details}


def _reason(exc):
    """把网络异常翻成一句能显示的话，避免把堆栈和内部地址暴露到界面。"""
    if isinstance(exc, urllib.error.HTTPError):
        return f'站点返回 HTTP {exc.code}'
    if isinstance(exc, urllib.error.URLError):
        return '站点无法访问（网络或证书问题）'
    if isinstance(exc, TimeoutError):
        return '站点响应超时'
    return '页面解析失败'
