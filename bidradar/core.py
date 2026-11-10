"""Local-first tender discovery. No third-party dependencies."""
import csv
import io
import json
import os
import re
import sqlite3
import threading
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

import crawler
import qcc

ROOT = Path(__file__).resolve().parent
DB = Path(os.environ.get('BIDRADAR_DB', str(ROOT / 'data' / 'bidradar.db')))
SEARCH_LOCK = threading.Lock()
# 采集与搜索各用一把锁：两者都会写 projects/matches，但互不阻塞对方的节奏
CRAWL_LOCK = threading.Lock()
# 单次搜索最多召回条数，每个主题每轮最多发起 3 次请求。
SEARCH_COUNT = 50

# 线索来源渠道。界面上按这个筛「搜索来的」和「爬来的」。
ORIGIN_LABELS = {'search': 'DeepSeek 搜索', 'crawl': '站点采集', 'qcc': '企查查', 'demo': '演示快照'}

def now():
    return datetime.now(timezone.utc).isoformat()

@contextmanager
def connect():
    DB.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('PRAGMA foreign_keys=ON')
    try:
        with conn:
            yield conn
    finally:
        conn.close()

def init():
    with connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS topics(id INTEGER PRIMARY KEY, name TEXT NOT NULL, keywords TEXT NOT NULL, excludes TEXT NOT NULL, region TEXT NOT NULL, created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS buyers(id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, followed INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS projects(id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL, content TEXT NOT NULL, source TEXT NOT NULL, fetched_at TEXT NOT NULL, published TEXT NOT NULL DEFAULT '', amount TEXT NOT NULL DEFAULT '', deadline TEXT NOT NULL DEFAULT '', stage TEXT NOT NULL DEFAULT '待核实', demo INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0, followup TEXT NOT NULL DEFAULT '待研究', notes TEXT NOT NULL DEFAULT '', is_notice INTEGER NOT NULL DEFAULT 1, origin TEXT NOT NULL DEFAULT 'search');
        CREATE TABLE IF NOT EXISTS matches(topic_id INTEGER REFERENCES topics(id) ON DELETE CASCADE, project_id INTEGER REFERENCES projects(id) ON DELETE CASCADE, reason TEXT NOT NULL, score INTEGER NOT NULL, PRIMARY KEY(topic_id,project_id));
        CREATE TABLE IF NOT EXISTS runs(id INTEGER PRIMARY KEY, topic_id INTEGER, topic_name TEXT, started_at TEXT NOT NULL, finished_at TEXT, status TEXT, count INTEGER DEFAULT 0, errors TEXT DEFAULT '[]', kind TEXT NOT NULL DEFAULT 'search');
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS workflows(id INTEGER PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, freq TEXT NOT NULL DEFAULT 'daily', at_time TEXT NOT NULL DEFAULT '09:00', interval_hours INTEGER NOT NULL DEFAULT 24, topic_ids TEXT NOT NULL DEFAULT '', auto_export INTEGER NOT NULL DEFAULT 0, last_run_at TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, channels TEXT NOT NULL DEFAULT 'search');
        CREATE TABLE IF NOT EXISTS workflow_runs(id INTEGER PRIMARY KEY, workflow_id INTEGER, workflow_name TEXT NOT NULL DEFAULT '', trigger TEXT NOT NULL DEFAULT 'manual', started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL DEFAULT '执行中', topics_total INTEGER NOT NULL DEFAULT 0, topics_ok INTEGER NOT NULL DEFAULT 0, found INTEGER NOT NULL DEFAULT 0, new_count INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL DEFAULT '[]', new_ids TEXT NOT NULL DEFAULT '', sources TEXT NOT NULL DEFAULT '[]');
        CREATE TABLE IF NOT EXISTS crawl_details(url TEXT PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL, published TEXT NOT NULL, checked_at TEXT NOT NULL, content_kind TEXT NOT NULL DEFAULT 'crawl_excerpt');
        CREATE TABLE IF NOT EXISTS qcc_evidence(project_id INTEGER PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE, qcc_id TEXT NOT NULL DEFAULT '', project_no TEXT NOT NULL DEFAULT '', budget TEXT NOT NULL DEFAULT '', bid_end_date TEXT NOT NULL DEFAULT '', buyer TEXT NOT NULL DEFAULT '', checked_at TEXT NOT NULL);
        ''')
        db.execute('BEGIN IMMEDIATE')
        project_columns = {r[1] for r in db.execute('PRAGMA table_info(projects)')}
        # 老库升级：补上形态标记列（1=疑似公告，0=疑似门户/列表/新闻）
        if 'is_notice' not in project_columns:
            db.execute('ALTER TABLE projects ADD COLUMN is_notice INTEGER NOT NULL DEFAULT 1')
        # 来源渠道：search=DeepSeek 搜索，crawl=站点采集，demo=演示快照。
        # 旧库里的行都是搜索来的，默认值已经对；只需把演示数据标回 demo。
        if 'origin' not in project_columns:
            db.execute('ALTER TABLE projects ADD COLUMN origin TEXT NOT NULL DEFAULT \'search\'')
            db.execute("UPDATE projects SET origin='demo' WHERE demo=1")
        if 'kind' not in {r[1] for r in db.execute('PRAGMA table_info(runs)')}:
            db.execute('ALTER TABLE runs ADD COLUMN kind TEXT NOT NULL DEFAULT \'search\'')
        if 'channels' not in {r[1] for r in db.execute('PRAGMA table_info(workflows)')}:
            db.execute("ALTER TABLE workflows ADD COLUMN channels TEXT NOT NULL DEFAULT 'search'")
        if 'sources' not in {r[1] for r in db.execute('PRAGMA table_info(workflow_runs)')}:
            db.execute("ALTER TABLE workflow_runs ADD COLUMN sources TEXT NOT NULL DEFAULT '[]'")
        evidence_columns = ('content_kind', 'published_source', 'first_seen_at', 'last_seen_at',
                            'notice_kind', 'official_deadline', 'official_deadline_at', 'deadline_evidence')
        for column in evidence_columns:
            if column not in project_columns:
                db.execute(f"ALTER TABLE projects ADD COLUMN {column} TEXT NOT NULL DEFAULT ''")
        if 'first_seen_at' not in project_columns:
            db.execute("""UPDATE projects SET first_seen_at=fetched_at, last_seen_at=fetched_at,
                         content_kind=CASE WHEN content='' OR content=title THEN 'title' ELSE 'legacy' END,
                         published_source=CASE WHEN published='' THEN '' ELSE 'legacy' END""")
        if 'notice_kind' not in project_columns:
            old_projects = db.execute('SELECT id,title,is_notice FROM projects').fetchall()
            db.executemany('UPDATE projects SET notice_kind=? WHERE id=?',
                           [(notice_kind(p['title'], p['is_notice']), p['id']) for p in old_projects])
            linked = db.execute('''SELECT t.*,m.project_id,p.title,p.content FROM matches m
                                   JOIN topics t ON t.id=m.topic_id JOIN projects p ON p.id=m.project_id''').fetchall()
            for row in linked:
                write_match(db, row['id'], row['project_id'], evaluate(dict(row), row['title'], row['content']))
        if 'buyer_source' not in project_columns:
            db.execute("ALTER TABLE projects ADD COLUMN buyer_source TEXT NOT NULL DEFAULT ''")
        if 'buyer_id' not in project_columns:
            db.execute('ALTER TABLE projects ADD COLUMN buyer_id INTEGER REFERENCES buyers(id)')
            for project in db.execute("""SELECT id,content FROM projects
                                        WHERE content_kind='official_detail' AND demo=0 AND is_notice=1""").fetchall():
                link_official_buyer(db, project['id'], project['content'])
        db.execute('CREATE INDEX IF NOT EXISTS projects_buyer_idx ON projects(buyer_id, id)')
        # 演示数据在 seed() 里是后写入的，这里顺手保证标记一致
        db.execute("UPDATE projects SET origin='demo' WHERE demo=1 AND origin<>'demo'")

def words(s):
    return [x.strip() for x in re.split(r'[,，;；\n]+', s) if x.strip()][:30]

# 地区栏里写这些词等同于「不限制地区」，不能当成真实地名拼进搜索词。
# 否则 query 会变成「OPC 招标 采购 不限」，正文永远匹配不到「不限」，全部线索被标成地区待核实。
UNLIMITED_REGIONS = ('不限', '不限地区', '不限区域', '不限制', '全部', '全部地区', '全国', '任意', '无')

def region_words(s):
    """地区词切分：把「不限/全国/全部」这类占位词当空处理，其余同 words()。"""
    return [w for w in words(s) if w not in UNLIMITED_REGIONS]

# ---- 公告形态判定 ----
# 搜索接口会把门户首页、栏目列表页、聚合导航页、广告位和新闻资讯一起返回。
# 下列规则在 57 条人工标注样本上命中噪音 21 条、误杀真公告 0 条（精确率 100%，召回 72%）。
PORTAL_SUFFIX = ('网', '局', '平台', '中心', '厅', '委员会', '采购', '招标信息', '信息')
NOTICE_ACTIONS = ('公告', '公示', '通知', '磋商', '中标', '成交', '谈判', '比选', '征集', '招募', '结果', '合同')
AGG_SITE_WORDS = ('招标网', '采购网', '招标信息网', '信息平台', '标讯', '乙方宝', '国信',
                  '政府采购网', '公共资源交易平台', '招标与采购网')
AD_WORDS = ('黄金会员', '即将跳转', '相关推荐', '已为您找到', '最新商机')
MEDIA_HOSTS = ('toutiao.com', 'jschina.com.cn', 'cnjxol.com', 'sohu.com', 'sina.cn')
MEDIA_TITLES = ('今日头条', '中国江苏网')

def is_notice(title, url):
    """1 = 疑似单条公告；0 = 疑似门户首页/栏目列表/聚合导航/广告/新闻资讯。"""
    t = str(title or '')
    compact = re.sub(r'\s+', '', t)
    head = re.split(r'[-|]', compact)[0]
    host = urllib.parse.urlsplit(str(url or '')).netloc.lower()
    if '_' in t and any(w in t for w in AGG_SITE_WORDS):
        return 0
    if re.search(r'第\s*\d+\s*页', t):
        return 0
    if any(w in t for w in AD_WORDS):
        return 0
    if len(head) <= 14 and head.endswith(PORTAL_SUFFIX) and not any(w in head for w in NOTICE_ACTIONS):
        return 0
    if any(host == h or host.endswith('.' + h) for h in MEDIA_HOSTS) or any(w in t for w in MEDIA_TITLES):
        return 0
    return 1

NOTICE_TYPES = (
    ('terminated', r'(?:终止|废标|流标)(?:公告|公示|通知|结果|[）)]|$)|采购失败|招标失败|取消(?:采购|招标)'),
    ('correction', r'(?:更正|澄清|变更|延期|补充)(?:公告|通知|公示|说明|[）)]|$)|^关于(?:更正|延期).{0,70}(?:公告|通知)$'),
    ('result', r'中标(?:公告|公示|结果|候选人)|成交(?:公告|公示|结果|候选人)|评审结果'),
    ('contract', r'合同(?:公告|公示|信息|备案)'),
    ('intent', r'采购意向|拟采购|单一来源公示|需求调查'),
    ('opportunity', r'招标公告|采购公告|投标邀请|采购邀请|资格预审|竞争性(?:磋商|谈判)|询价|询比|比选|征集|招募|遴选'),
)

INTENT_HOST = 'cgyx.ccgp.gov.cn'

def intent_notice_url(url):
    if not url:
        return False
    page = urllib.parse.urlsplit(url)
    return (page.hostname == INTENT_HOST and page.path.rstrip('/') == '/cgyx/pub/details'
            and bool(urllib.parse.parse_qs(page.query).get('groupId')))

def notice_kind(title, flag, url=''):
    if flag:
        if intent_notice_url(url):
            return 'intent'
        # 去掉转载站附加的站名，避免把它当成公告阶段。
        heading = re.split(r'\s+[|｜]|[|｜]|\s+-\s+', title, maxsplit=1)[0]
        head, separator, tail = heading.rpartition('-')
        if separator and any(word in tail for word in AGG_SITE_WORDS):
            heading = head
        for kind, pattern in NOTICE_TYPES:
            if re.search(pattern, heading):
                return kind
    return 'other'

DEADLINE_RE = re.compile(
    r'(?:投标(?:文件(?:提交|递交)?)?|(?:响应|申请)文件(?:提交|递交)?|'
    r'(?:提交|递交)(?:投标|响应|申请)文件)(?:的)?\s*截止(?:时间|日期)'
    r'(?:\s*[、，,]\s*开标(?:时间)?(?:和地点)?)?\s*[:：]?\s*'
    r'(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})(?!\d)(?:\s*日)?'
    r'(?:[ T\t]*(\d{1,2})\s*[:：时点]\s*(\d{1,2})(?!\d)(?:\s*分)?)?'
)

def extract_deadline(content):
    """仅从明确的投标/响应截止标签提取日期，互相矛盾的时间留待人工核实。"""
    dates, times, evidence = set(), set(), ''
    for match in DEADLINE_RE.finditer(content):
        year, month, day, hour, minute = match.groups()
        try:
            date = datetime(int(year), int(month), int(day))
            if hour is not None:
                clock = date.replace(hour=int(hour), minute=int(minute))
                times.add(clock.isoformat(timespec='minutes') + '+08:00')
        except ValueError:
            continue
        dates.add(date.date().isoformat())
        if not evidence or hour is not None:
            evidence = ' '.join(match.group().split())
    if len(dates) != 1 or len(times) > 1:
        return '', '', ''
    return dates.pop(), next(iter(times), ''), evidence

def valid_url(raw):
    try:
        p = urllib.parse.urlsplit(raw)
        if p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password:
            return None
        query = [(k,v) for k,v in urllib.parse.parse_qsl(p.query, keep_blank_values=True) if not k.lower().startswith('utm_')]
        return urllib.parse.urlunsplit((p.scheme, p.netloc.lower(), p.path, urllib.parse.urlencode(query), ''))
    except ValueError:
        return None

def normalized(text):
    return unicodedata.normalize('NFKC', text).casefold()

def contains_word(text, word):
    word = normalized(word)
    if re.fullmatch(r'[a-z0-9]+', word):
        return re.search(r'(?<![a-z0-9])' + word + r'(?![a-z0-9])', text) is not None
    return word in text

OPC_BUSINESS_HINTS = ('一人公司', '单人公司', '创业社区', '创业孵化', '孵化', '社区运营')

def opc_business_topic(topic):
    context = normalized(topic.get('name', '') + ' ' + topic['keywords'])
    return contains_word(normalized(topic['keywords']), 'opc') and any(
        hint in context for hint in OPC_BUSINESS_HINTS)

def evaluate(topic, title, content):
    heading = normalized(title)
    text = heading + ' ' + normalized(content)
    if any(contains_word(text, w) for w in words(topic['excludes'])):
        return None
    regions = region_words(topic['region'])
    # 地区不再是硬过滤：搜索返回的正文常被截断，公告标题也常只写「盐城经开区」不写「江苏」，
    # 硬过滤会把这些本省公告全部误杀。现在改为命中加分、未命中保留并标注待核实。
    region_hit = [x for x in regions if normalized(x) in text]
    matched = [w for w in topic_keywords(topic) if contains_word(text, w)]
    if not matched or not any(w in text for w in ['招标', '采购', '磋商', '成交', '中标', '征集', '招募', '比选']):
        return None
    title_hits = [w for w in matched if contains_word(heading, w)]
    score = 50 + len(matched)*10 + (15 if title_hits else 0) + (10 if region_hit else 0)
    reason = ('标题命中：' if title_hits == matched else '关键词命中：') + '、'.join(matched)
    if (len(matched) == 1 and normalized(matched[0]) == 'opc' and opc_business_topic(topic)
            and not any(term in text for term in ('创业', '孵化', '社区运营', '一人公司', '单人公司'))):
        industrial = re.search(r'opc[\s-]*(?:ua|da|协议)|工业通信|组态软件', text)
        score -= 35 if industrial else 20
        reason += '；缩写语境待核实'
    if regions:
        reason += ('；地区命中：' + '、'.join(region_hit)) if region_hit else '；地区待核实（正文未出现：' + '、'.join(regions) + '）'
    return min(100, score), reason

def topic_keywords(topic):
    terms = words(topic['keywords'])
    if opc_business_topic(topic):
        terms.extend(('一人公司', '单人公司'))
    unique = {}
    for term in terms:
        unique.setdefault(normalized(term), term)
    return list(unique.values())

def queries(topic, search_round=0, *, intents=False):
    terms = topic_keywords(topic)
    if not terms:
        return []
    broad = ([f'site:{INTENT_HOST} 采购意向 {term}' for term in terms] if intents else
             [f'{term} 招标 采购' for term in terms])
    regions = dict.fromkeys(region_words(topic['region']))
    rotation = [f'{broad[0]} {region}' for region in regions] + broad[2:]
    return broad[:2] + ([rotation[search_round % len(rotation)]] if rotation else [])

def _search_query(query, official_only):
    suffix = ' site:ccgp.gov.cn OR site:ggzy.gov.cn' if official_only else ''
    return query[:128-len(suffix)] + suffix

def deepseek_search(query, key, official_only=False):
    # count 决定单次召回条数：最初写死 10，导致每个主题每轮最多只有 30 条候选。
    body = dict(query=_search_query(query, official_only), count=SEARCH_COUNT, need_content=True)
    req = urllib.request.Request('https://api.deepseek.com/v1/tools/search', data=json.dumps(body).encode(), headers={'Authorization': 'Bearer '+key, 'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            data = json.load(response)
    except urllib.error.HTTPError as e:
        messages = {401:'搜索密钥无效',402:'DeepSeek 账户余额不足',403:'搜索服务拒绝访问',429:'搜索服务限流，请稍后重试',432:'搜索额度不足',433:'搜索额度不足'}
        raise RuntimeError(messages.get(e.code, f'搜索服务返回 HTTP {e.code}')) from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        raise RuntimeError('搜索服务连接失败或响应异常，请检查网络后重试') from None
    if not isinstance(data.get('results'), list):
        raise RuntimeError('搜索服务未返回有效结果')
    return [{**r, 'content': r.get('content') or r.get('summary') or r.get('snippet') or '', 'published_date': r.get('date') or ''} for r in data['results'] if isinstance(r,dict)]

def config():
    with connect() as db:
        settings = dict(db.execute('SELECT key,value FROM settings').fetchall())
    return {'has_key':bool(os.environ.get('DEEPSEEK_API_KEY','').strip()),
            'has_qcc_key':bool(os.environ.get('QCC_APP_KEY','').strip() and os.environ.get('QCC_SECRET_KEY','').strip()),
            'interval_hours':int(settings.get('interval_hours','0')), 'official_only':settings.get('official_only','false')=='true'}

def fetch_page(project_id):
    key = os.environ.get('DEEPSEEK_API_KEY','').strip()
    if not key:
        raise ValueError('请先配置 DEEPSEEK_API_KEY 并重启程序')
    with connect() as db:
        row = db.execute('SELECT url FROM projects WHERE id=?',(project_id,)).fetchone()
    if not row:
        raise ValueError('项目不存在')
    req = urllib.request.Request('https://api.deepseek.com/v1/tools/fetch',data=json.dumps({'url':row['url']}).encode(),headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=40) as response:
            result = json.load(response)
    except urllib.error.HTTPError as e:
        raise ValueError({401:'DeepSeek 密钥无效',402:'DeepSeek 余额不足',403:'目标页面被安全策略阻止',404:'无法提取该网页，可能受网站访问限制',429:'请求过于频繁，请稍后重试'}.get(e.code,'正文获取失败，请稍后重试')) from None
    except (urllib.error.URLError,TimeoutError,json.JSONDecodeError):
        raise ValueError('正文获取连接失败，已保留原有内容') from None
    content = result.get('content') if isinstance(result, dict) else None
    if not isinstance(content,str) or not content.strip():
        raise ValueError('未获取到正文，已保留原有内容')
    with connect() as db:
        db.execute('BEGIN IMMEDIATE')
        project = db.execute('SELECT * FROM projects WHERE id=?',(project_id,)).fetchone()
        if project is None:
            raise ValueError('项目不存在')
        content = content.strip()[:100000]
        kind = 'official_detail' if official_host(project['source']) else 'fetch'
        if content != project['title'] and prefer_content(project, content, kind):
            deadline = deadline_fields(project['notice_kind'], content, kind)
            db.execute('''UPDATE projects SET content=?,content_kind=?,fetched_at=?,demo=0,
                          official_deadline=?,official_deadline_at=?,deadline_evidence=? WHERE id=?''',
                       (content, kind, now(), *deadline, project_id))
            if kind == 'official_detail' and project['is_notice'] and project['buyer_source'] != 'manual':
                link_official_buyer(db, project_id, content)
            for topic in db.execute('SELECT * FROM topics').fetchall():
                match = evaluate(dict(topic), project['title'], content)
                write_match(db, topic['id'], project_id, match)

CONTENT_PRIORITY = {'title': 0, 'search': 1, 'crawl_excerpt': 1, 'legacy': 2,
                    'fetch': 3, 'official_detail': 4}
DATE_PRIORITY = {'': 0, 'search': 1, 'qcc': 1, 'legacy': 2, 'crawl': 3}

def official_host(host):
    return host.endswith('.gov.cn')

# 只从正文中明确标注的采购人、招标人字段识别单位，避免误用采购代理机构名称。
BUYER_FIELD = re.compile(r'(?:^|\n)[ \t]*(?:\d+[.．、][ \t]*)?招[ \t]*标[ \t]*人[ \t]*[:：][ \t]*([^\n]+)')
BUYER_SECTION = re.compile(
    r'(?:^|\n)[ \t]*(?:\d+[.．、][ \t]*)?采购人信息[ \t]*[:：]?[ \t]*\n'
    r'[ \t]*(?:单[ \t]*位[ \t]*)?名[ \t]*称[ \t]*[:：][ \t]*([^\n]+)'
)
BUYER_FIELD_END = re.compile(r'[ \t]*(?:地[ \t]*址|联系(?:人|方式|电话)|采购代理机构)[ \t]*[:：]')

def buyer_name(value):
    if not isinstance(value, str) or any(unicodedata.category(char).startswith('C') for char in value):
        raise ValueError('采购人名称格式无效')
    name = ' '.join(value.split())
    if len(name) > 160:
        raise ValueError('采购人名称最多 160 字')
    return name

def buyer_name_key(name):
    return normalized(name)

def extract_buyer(content):
    content = content.replace('\r\n', '\n')
    names = {}
    for pattern in (BUYER_SECTION, BUYER_FIELD):
        for match in pattern.finditer(content):
            value = BUYER_FIELD_END.split(match[1], maxsplit=1)[0].strip(' \t*；;')
            if (len(value) < 2 or re.search(r'[:：/／；;<>《》、]|采购代理机构|详见|不适用|待定', value)
                    or value in ('无', '暂无', '—', '-')):
                continue
            try:
                name = buyer_name(value)
            except ValueError:
                continue
            names[buyer_name_key(name)] = name
    return next(iter(names.values())) if len(names) == 1 else ''

def get_buyer_id(db, name):
    key = buyer_name_key(name)
    db.execute('INSERT INTO buyers(name,name_key) VALUES(?,?) ON CONFLICT(name_key) DO NOTHING', (name, key))
    return db.execute('SELECT id FROM buyers WHERE name_key=?', (key,)).fetchone()['id']

def link_official_buyer(db, project_id, content):
    name = extract_buyer(content)
    if name:
        bid = get_buyer_id(db, name)
        db.execute("""UPDATE projects SET buyer_id=?,buyer_source='official'
                      WHERE id=? AND buyer_source<>'manual'""", (bid, project_id))

def link_listed_buyer(db, project_id, value):
    try:
        name = buyer_name(value)
    except ValueError:
        return
    if name and db.execute("""SELECT 1 FROM projects WHERE id=?
                              AND buyer_source IN ('','official_listing')""", (project_id,)).fetchone():
        db.execute("UPDATE projects SET buyer_id=?,buyer_source='official_listing' WHERE id=?",
                   (get_buyer_id(db, name), project_id))

def prefer_content(existing, content, kind):
    if not existing or not existing['content']:
        return True
    priority = CONTENT_PRIORITY[kind]
    previous = CONTENT_PRIORITY.get(existing['content_kind'], 0)
    return priority > previous or (priority == previous and len(content) >= len(existing['content']))

def deadline_fields(kind, content, content_kind):
    if kind in ('opportunity', 'correction') and content_kind == 'official_detail':
        return extract_deadline(content)
    return '', '', ''

def write_match(db, tid, pid, match):
    if match:
        db.execute('''INSERT INTO matches VALUES(?,?,?,?)
                      ON CONFLICT(topic_id,project_id) DO UPDATE SET reason=excluded.reason,score=excluded.score''',
                   (tid, pid, *match))
    else:
        db.execute('DELETE FROM matches WHERE topic_id=? AND project_id=?', (tid, pid))

def save_result(db, topic, item, origin):
    """写入一条线索并建立主题匹配。返回 (项目id, 是否新增)，不匹配则返回 None。

    origin 只在首次写入时生效：同一条公告既被搜到又被爬到，保留最早那条渠道，
    这样「来源」筛选不会因为后一轮走了别的通道而漂移。
    """
    url = valid_url(str(item.get('url', '')))
    if not url:
        return None
    title = str(item.get('title') or '').strip()[:500]
    content = str(item.get('content') or '').strip()[:20000]
    source = urllib.parse.urlsplit(url).hostname
    content_kind = 'search'
    if origin == 'crawl':
        content_kind = ('official_detail' if item.get('content_kind') == 'official_detail'
                        and official_host(source) else 'crawl_excerpt')
    if not content or content == title:
        content, content_kind = title, 'title'
    published = str(item.get('published_date') or item.get('published') or '').strip()[:100]
    date_source = origin if published else ''

    # 先取得写锁，再读取旧证据，避免并行搜索/采集各自按同一份旧数据覆盖。
    if not db.in_transaction:
        db.execute('BEGIN IMMEDIATE')
    existing = db.execute('SELECT * FROM projects WHERE url=?', (url,)).fetchone()
    if existing:
        if not prefer_content(existing, content, content_kind):
            content, content_kind = existing['content'], existing['content_kind']
            if origin != 'crawl':
                title = existing['title']
        title = title or existing['title']
        if not published or DATE_PRIORITY[date_source] < DATE_PRIORITY.get(existing['published_source'], 0):
            published, date_source = existing['published'], existing['published_source']

    match = evaluate(topic, title, content)
    if not match and not existing:
        return None
    flag = is_notice(title, url)
    kind = notice_kind(title, flag, url)
    seen = now()
    if existing:
        pid = existing['id']
        text_changed = title != existing['title'] or content != existing['content']
        evidence_changed = text_changed or content_kind != existing['content_kind'] or kind != existing['notice_kind']
        deadline = (deadline_fields(kind, content, content_kind) if evidence_changed else
                    (existing['official_deadline'], existing['official_deadline_at'], existing['deadline_evidence']))
        db.execute('''UPDATE projects SET title=?,content=?,published=?,published_source=?,last_seen_at=?,fetched_at=?,
                      content_kind=?,is_notice=?,notice_kind=?,official_deadline=?,official_deadline_at=?,
                      deadline_evidence=?,demo=0 WHERE id=?''',
                   (title, content, published, date_source, seen, seen if evidence_changed else existing['fetched_at'],
                    content_kind, flag, kind, *deadline, pid))
        if text_changed:
            linked = db.execute('''SELECT t.* FROM topics t JOIN matches m ON t.id=m.topic_id
                                   WHERE m.project_id=? AND t.id<>?''', (pid, topic['id'])).fetchall()
            for linked_topic in linked:
                write_match(db, linked_topic['id'], pid, evaluate(dict(linked_topic), title, content))
    else:
        deadline = deadline_fields(kind, content, content_kind)
        pid = db.execute('''INSERT INTO projects(url,title,content,source,fetched_at,published,is_notice,origin,
                            content_kind,published_source,first_seen_at,last_seen_at,notice_kind,
                            official_deadline,official_deadline_at,deadline_evidence)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                         (url, title, content, source, seen, published, flag, origin, content_kind,
                          date_source, seen, seen, kind, *deadline)).lastrowid
    if flag and content_kind == 'official_detail' and (
        not existing or (evidence_changed and existing['buyer_source'] != 'manual')
    ):
        link_official_buyer(db, pid, content)
    if flag and origin == 'crawl' and official_host(source) and item.get('buyer_source') == 'official_listing':
        link_listed_buyer(db, pid, item.get('buyer_name'))
    write_match(db, topic['id'], pid, match)
    return (pid, existing is None) if match else None

def qcc_search(topic_id, keyword, *, area_code='', msg_type='', page_index=1, provider=None):
    """用户手动查询一页，保留原文链接并复用主题匹配和网址去重。"""
    if not SEARCH_LOCK.acquire(blocking=False):
        raise ValueError('已有搜索任务正在执行，请稍后重试。')
    try:
        with connect() as db:
            row = db.execute('SELECT * FROM topics WHERE id=?', (topic_id,)).fetchone()
            if row is None:
                raise ValueError('关注主题不存在。')
            topic = dict(row)
        search = provider or qcc.search
        response = search(keyword, os.environ.get('QCC_APP_KEY', '').strip(),
                          os.environ.get('QCC_SECRET_KEY', '').strip(),
                          area_code=area_code, msg_type=msg_type, page_index=page_index)
        found, created, missing_url, unmatched = set(), set(), 0, 0
        with connect() as db:
            if not db.execute('SELECT 1 FROM topics WHERE id=?', (topic_id,)).fetchone():
                raise ValueError('关注主题已删除，查询结果未导入。')
            for raw in response['items']:
                item = qcc.normalize(raw)
                if not item or not valid_url(item['url']):
                    missing_url += 1
                    continue
                stored = save_result(db, topic, item, 'qcc')
                if not stored:
                    unmatched += 1
                    continue
                project_id, is_new = stored
                found.add(project_id)
                if is_new:
                    created.add(project_id)
                db.execute('''INSERT INTO qcc_evidence(project_id,qcc_id,project_no,budget,bid_end_date,buyer,checked_at)
                              VALUES(?,?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET
                              qcc_id=CASE WHEN excluded.qcc_id<>'' THEN excluded.qcc_id ELSE qcc_id END,
                              project_no=CASE WHEN excluded.project_no<>'' THEN excluded.project_no ELSE project_no END,
                              budget=CASE WHEN excluded.budget<>'' THEN excluded.budget ELSE budget END,
                              bid_end_date=CASE WHEN excluded.bid_end_date<>'' THEN excluded.bid_end_date ELSE bid_end_date END,
                              buyer=CASE WHEN excluded.buyer<>'' THEN excluded.buyer ELSE buyer END,
                              checked_at=excluded.checked_at''',
                           (project_id, item['qcc_id'], item['project_no'], item['budget'],
                            item['bid_end_date'], item['buyer'], now()))
            db.execute('''INSERT INTO runs(topic_id,topic_name,started_at,finished_at,status,count,errors,kind)
                          VALUES(?,?,?,?,?,?,?,?)''',
                       (topic_id, topic['name'], now(), now(), '成功', len(found), '[]', 'qcc'))
        return {'count':len(found), 'new':len(created), 'returned':len(response['items']),
                'total':response['total'], 'page':response['page'],
                'missing_url':missing_url, 'unmatched':unmatched}
    finally:
        SEARCH_LOCK.release()


def scan(topic_id, provider=None, query_cache=None, *, intents=False):
    key = os.environ.get('DEEPSEEK_API_KEY','').strip()
    if not key and provider is None:
        raise ValueError('请在 .env 中配置 DEEPSEEK_API_KEY，并重启程序。')
    if not SEARCH_LOCK.acquire(blocking=False):
        raise ValueError('已有搜索任务正在执行，请稍后重试。')
    run_id = None
    try:
        with connect() as db:
            row = db.execute('SELECT * FROM topics WHERE id=?',(topic_id,)).fetchone()
            if row is None:
                raise ValueError('主题不存在')
            topic = dict(row)
            kind = 'intent' if intents else 'search'
            search_round = db.execute('SELECT count(*) FROM runs WHERE topic_id=? AND kind=?',
                                      (topic_id, kind)).fetchone()[0]
            run_id = db.execute('INSERT INTO runs(topic_id,topic_name,started_at,status,kind) VALUES(?,?,?,?,?)',
                                (topic_id, topic['name'], now(), '执行中', kind)).lastrowid
        errors, found, success = [], set(), 0
        created = set()   # 本轮真正新建的项目（区别于命中已有项目），工作流汇总用
        official_only = not intents and config()['official_only']
        seen_queries = set()
        for query in queries(topic, search_round, intents=intents):
            effective_query = query if provider else _search_query(query, official_only)
            if effective_query in seen_queries:
                continue
            seen_queries.add(effective_query)
            cache_key = (id(provider), key, official_only, effective_query)
            try:
                if query_cache is not None and cache_key in query_cache:
                    results = query_cache[cache_key]
                else:
                    results = (provider(query) if provider else deepseek_search(query,key,official_only))[:SEARCH_COUNT]
                    if query_cache is not None:
                        query_cache[cache_key] = results
                success += 1
                with connect() as db:
                    # A topic deleted during a request must not produce orphan matches.
                    if not db.execute('SELECT id FROM topics WHERE id=?',(topic_id,)).fetchone():
                        raise ValueError('主题已删除')
                    for result in results:
                        if not isinstance(result,dict):
                            continue
                        if intents and not intent_notice_url(valid_url(str(result.get('url') or ''))):
                            continue
                        stored = save_result(db, topic, result, 'search')
                        if not stored:
                            continue
                        pid, is_new = stored
                        found.add(pid)
                        if is_new:
                            created.add(pid)
            except Exception as e:
                errors.append({'query':query,'message':str(e) if isinstance(e,(ValueError,RuntimeError)) else '本轮处理失败，请稍后重试'})
        status = '成功' if not errors else ('部分成功' if success else '失败')
        with connect() as db:
            db.execute('UPDATE runs SET finished_at=?,status=?,count=?,errors=? WHERE id=?',(now(),status,len(found),json.dumps(errors,ensure_ascii=False),run_id))
        return {'status':status,'count':len(found),'found_ids':sorted(found),
                'new':len(created),'new_ids':sorted(created),'errors':errors}
    finally:
        SEARCH_LOCK.release()

# ---- 定向采集 ----
# 和 scan() 走的是两套数据源、同一套判定：读官方栏目页拿到的那批公告，
# 仍然要过 evaluate() 的关键词/招采词/地区规则与 is_notice() 的形态判定。
def _cached_crawl_details(urls):
    if not urls:
        return {}
    cutoff = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
    with connect() as db:
        placeholders = ','.join('?' for _ in urls)
        rows = db.execute(f'SELECT *, checked_at >= ? AS fresh FROM crawl_details WHERE url IN ({placeholders})',
                          (cutoff, *urls))
        return {row['url']: dict(row) for row in rows}


def _crawl_topics(topics, collector=None):
    """抓取栏目一次，再将同一份公告分别与参与主题匹配。"""
    if not CRAWL_LOCK.acquire(blocking=False):
        raise ValueError('已有采集任务正在执行，请稍后重试。')
    try:
        started = now()
        keywords = list(dict.fromkeys(word for topic in topics for word in topic_keywords(topic)))
        errors = []
        try:
            if collector is None:
                payload = crawler.collect(keywords=keywords, lookup_details=_cached_crawl_details)
            else:
                with connect() as db:
                    known = {r[0] for r in db.execute('SELECT url FROM projects')}
                payload = collector(known=known, keywords=keywords)
        except Exception:
            # 采集器内部已逐源容错，还能抛到这里说明是意料之外的问题
            payload = {'items': [], 'sources': []}
            errors.append({'query': 'collect', 'message': '采集失败，请稍后重试'})
        sources = payload.get('sources') or []
        for src in sources:
            if src.get('error'):
                errors.append({'query': src.get('name', ''), 'message': src['error']})
        items = payload.get('items') or []
        has_data = bool(items) or any(not src.get('error') for src in sources)
        status = ('部分成功' if has_data else '失败') if errors else '成功'
        finished = now()
        results = {}
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            expiry = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()
            db.execute('DELETE FROM crawl_details WHERE checked_at < ?', (expiry,))
            db.executemany('INSERT OR REPLACE INTO crawl_details(url,title,content,published,checked_at,content_kind) VALUES(?,?,?,?,?,?)',
                           [(url, d['title'], d['content'], d['published'], finished, d['content_kind'])
                            for url, d in (payload.get('details') or {}).items()])
            for topic in topics:
                topic_id = topic['id']
                if not db.execute('SELECT 1 FROM topics WHERE id=?', (topic_id,)).fetchone():
                    raise ValueError('主题已删除')
                found, created = set(), set()
                for item in items:
                    stored = save_result(db, topic, item, 'crawl')
                    if stored:
                        pid, is_new = stored
                        found.add(pid)
                        if is_new:
                            created.add(pid)
                db.execute('INSERT INTO runs(topic_id,topic_name,started_at,finished_at,status,count,errors,kind) VALUES(?,?,?,?,?,?,?,?)',
                           (topic_id, topic['name'], started, finished, status, len(found),
                            json.dumps(errors, ensure_ascii=False), 'crawl'))
                results[topic_id] = {'status': status, 'count': len(found), 'new': len(created),
                                     'found_ids': sorted(found), 'new_ids': sorted(created), 'errors': errors}
        return results, sources
    finally:
        CRAWL_LOCK.release()

def crawl_topic(topic_id, collector=None):
    with connect() as db:
        row = db.execute('SELECT * FROM topics WHERE id=?', (topic_id,)).fetchone()
    if row is None:
        raise ValueError('主题不存在')
    results, sources = _crawl_topics([dict(row)], collector)
    return {**results[topic_id], 'sources': sources}


# ---- 自动工作流 ----
WORKFLOW_LOCK = threading.Lock()
WORKFLOW_FREQS = ('daily', 'interval')
WORKFLOW_INTERVALS = (1, 2, 3, 6, 12, 24)
WORKFLOW_CHANNELS = ('search', 'crawl', 'both')

def workflows():
    with connect() as db:
        return [dict(x) for x in db.execute('SELECT * FROM workflows ORDER BY id')]

def save_workflow(data):
    """新建或修改工作流。只传部分字段时按字段合并，避免旧页面数据把其他字段覆盖回去。"""
    wid, base = None, None
    with connect() as db:
        if data.get('id'):
            wid = int(data['id'])
            row = db.execute('SELECT * FROM workflows WHERE id=?', (wid,)).fetchone()
            if row is None:
                raise ValueError('工作流不存在')
            base = dict(row)
    def pick(key, default):
        if key in data and data[key] is not None:
            return data[key]
        return base[key] if base else default
    name = str(pick('name', '自动工作流') or '').strip() or '自动工作流'
    if len(name) > 60:
        raise ValueError('工作流名称不得超过 60 字')
    freq = str(pick('freq', 'daily'))
    if freq not in WORKFLOW_FREQS:
        raise ValueError('执行频率只能是「每天」或「按间隔」')
    channels = str(pick('channels', 'search'))
    if channels not in WORKFLOW_CHANNELS:
        raise ValueError('工作流通道只能是「DeepSeek 搜索」、「站点采集」或「两者」')
    at_time = str(pick('at_time', '09:00')).strip()
    try:
        hh, mm = (int(x) for x in at_time.split(':'))
        if not (0 <= hh < 24 and 0 <= mm < 60):
            raise ValueError
        at_time = f'{hh:02d}:{mm:02d}'
    except (ValueError, TypeError):
        raise ValueError('执行时间格式应为 HH:MM') from None
    try:
        interval = int(pick('interval_hours', 24))
    except (TypeError, ValueError):
        raise ValueError('间隔小时无效') from None
    if interval not in WORKFLOW_INTERVALS:
        raise ValueError('间隔小时只能是 1/2/3/6/12/24')
    raw = pick('topic_ids', []) or []
    if isinstance(raw, str):
        raw = [x for x in raw.split(',') if x.strip()]
    picked = []
    with connect() as db:
        valid = {r[0] for r in db.execute('SELECT id FROM topics')}
    for x in raw:
        text = str(x).strip()
        if not text.isdigit() or int(text) not in valid:
            raise ValueError('参与主题无效，请重新选择')
        if int(text) not in picked:
            picked.append(int(text))
    fields = (name, 1 if pick('enabled', 0) else 0, freq, at_time, interval,
              ','.join(str(i) for i in picked), 1 if pick('auto_export', 0) else 0, channels)
    with connect() as db:
        if wid is not None:
            db.execute('UPDATE workflows SET name=?,enabled=?,freq=?,at_time=?,interval_hours=?,topic_ids=?,auto_export=?,channels=? WHERE id=?', fields + (wid,))
        else:
            wid = db.execute('INSERT INTO workflows(name,enabled,freq,at_time,interval_hours,topic_ids,auto_export,channels,created_at) VALUES(?,?,?,?,?,?,?,?,?)', fields + (now(),)).lastrowid
            # 新建时把「上次执行」记为现在：否则若今天的时点已过，调度器会立刻补跑一整轮，
            # 用户只是想先保存配置却被扣掉几十次请求。想立刻看效果可以点「立即运行」。
            db.execute('UPDATE workflows SET last_run_at=? WHERE id=?', (now(), wid))
    return wid

def delete_workflow(wid):
    with connect() as db:
        db.execute('DELETE FROM workflows WHERE id=?', (int(wid),))

def workflow_due(wf, ref=None):
    """判断该工作流此刻是否到期。ref 传本地时间（naive），便于测试注入。"""
    if not wf.get('enabled'):
        return False
    ref = ref or datetime.now()
    last = str(wf.get('last_run_at') or '')
    if str(wf.get('freq') or 'daily') == 'interval':
        if not last:
            return True
        try:
            last_utc = datetime.fromisoformat(last)
        except ValueError:
            return True
        if last_utc.tzinfo is None:
            last_utc = last_utc.replace(tzinfo=timezone.utc)
        return (datetime.now(timezone.utc) - last_utc).total_seconds() >= int(wf.get('interval_hours') or 24) * 3600
    try:
        hh, mm = (int(x) for x in str(wf.get('at_time') or '09:00').split(':'))
    except ValueError:
        hh, mm = 9, 0
    target = ref.replace(hour=hh, minute=mm, second=0, microsecond=0)
    if ref < target:
        return False
    if not last:
        return True
    try:
        last_local = datetime.fromisoformat(last).astimezone().replace(tzinfo=None)
    except ValueError:
        return True
    return last_local < target

def _failed_channel(message):
    return {'status': '失败', 'count': 0, 'new': 0, 'found_ids': [], 'new_ids': [],
            'errors': [{'message': message}]}


def run_workflow(workflow_id=None, trigger='manual', topic_ids=None, provider=None, collector=None):
    """共享采集结果和相同查询的响应，按主题分别匹配，再汇总本轮线索。"""
    if not WORKFLOW_LOCK.acquire(blocking=False):
        raise ValueError('已有一轮工作流正在执行，请稍后再试。')
    try:
        wf = None
        if workflow_id is not None:
            with connect() as db:
                row = db.execute('SELECT * FROM workflows WHERE id=?', (int(workflow_id),)).fetchone()
            if row is None:
                raise ValueError('工作流不存在')
            wf = dict(row)
        mode = wf['channels'] if wf else 'search'
        has_search = provider is not None or bool(os.environ.get('DEEPSEEK_API_KEY', '').strip())
        missing_key = '请在 .env 中配置 DEEPSEEK_API_KEY，并重启程序。'
        if mode == 'search' and not has_search:
            raise ValueError(missing_key)
        with connect() as db:
            topics = [dict(x) for x in db.execute('SELECT * FROM topics ORDER BY id')]
        known = {t['id']: t for t in topics}
        if topic_ids is not None:
            want = [int(x) for x in topic_ids]
        elif wf and wf['topic_ids']:
            want = [int(x) for x in str(wf['topic_ids']).split(',') if x.strip()]
        else:
            want = [t['id'] for t in topics]
        targets = [known[i] for i in dict.fromkeys(want) if i in known]
        if not targets:
            raise ValueError('没有可执行的主题，请先在「关注主题」里创建')
        started = now()
        with connect() as db:
            run_id = db.execute('INSERT INTO workflow_runs(workflow_id,workflow_name,trigger,started_at,topics_total) VALUES(?,?,?,?,?)',
                (wf['id'] if wf else 0, wf['name'] if wf else '手动运行', trigger, started, len(targets))).lastrowid
        crawl_results, sources = {}, []
        if mode in ('crawl', 'both'):
            try:
                crawl_results, sources = _crawl_topics(targets, collector)
            except (ValueError, RuntimeError) as e:
                crawl_results = {topic['id']: _failed_channel(str(e)) for topic in targets}
        query_cache = {}
        detail, found_ids, new_ids = [], set(), set()
        for topic in targets:
            tid = topic['id']
            results = {}
            if mode in ('search', 'both'):
                if has_search:
                    try:
                        results['search'] = scan(tid, provider, query_cache=query_cache)
                    except (ValueError, RuntimeError) as e:
                        results['search'] = _failed_channel(str(e))
                else:
                    results['search'] = _failed_channel(missing_key)
            if tid in crawl_results:
                results['crawl'] = crawl_results[tid]
            channels = {}
            for channel, result in results.items():
                channels[channel] = {key: result[key] for key in ('status', 'count', 'new')}
                channels[channel]['errors'] = [error['message'] for error in result['errors']]
            found = {pid for result in results.values() for pid in result['found_ids']}
            new = {pid for result in results.values() for pid in result['new_ids']}
            statuses = {result['status'] for result in results.values()}
            status = statuses.pop() if len(statuses) == 1 else '部分成功'
            detail.append({'topic_id': tid, 'name': topic['name'], 'status': status,
                           'count': len(found), 'new': len(new), 'channels': channels,
                           'errors': list(dict.fromkeys(error for channel in channels.values() for error in channel['errors']))})
            found_ids.update(found)
            new_ids.update(new)
        ok = sum(row['status'] != '失败' for row in detail)
        status = '成功' if all(row['status'] == '成功' for row in detail) else ('部分成功' if ok else '失败')
        with connect() as db:
            db.execute('UPDATE workflow_runs SET finished_at=?,status=?,topics_ok=?,found=?,new_count=?,detail=?,new_ids=?,sources=? WHERE id=?',
                (now(), status, ok, len(found_ids), len(new_ids), json.dumps(detail, ensure_ascii=False),
                 ','.join(str(i) for i in sorted(new_ids)), json.dumps(sources, ensure_ascii=False), run_id))
            if wf:
                db.execute('UPDATE workflows SET last_run_at=? WHERE id=?', (started, wf['id']))
        result = {'id': run_id, 'status': status, 'topics_total': len(targets), 'topics_ok': ok,
                  'found': len(found_ids), 'new': len(new_ids), 'detail': detail, 'sources': sources}
        if wf and wf.get('auto_export'):
            try:
                folder = DB.parent / 'exports'
                folder.mkdir(parents=True, exist_ok=True)
                path = folder / f'workflow-run-{run_id}.csv'
                path.write_text(export_csv(run_id=run_id), encoding='utf-8')
                result['exported'] = str(path)
            except OSError:
                result['exported'] = ''
        return result
    finally:
        WORKFLOW_LOCK.release()

PROJECT_VIEW = """SELECT p.*,COALESCE(b.name,'') AS buyer_name,
                  COALESCE(q.qcc_id,'') AS qcc_id,COALESCE(q.project_no,'') AS qcc_project_no,
                  COALESCE(q.budget,'') AS qcc_budget,COALESCE(q.bid_end_date,'') AS qcc_bid_end_date,
                  COALESCE(q.buyer,'') AS qcc_buyer,(q.project_id IS NOT NULL) AS qcc_available
                  FROM projects p LEFT JOIN buyers b ON b.id=p.buyer_id
                  LEFT JOIN qcc_evidence q ON q.project_id=p.id"""
BUYER_PAGE_SIZE = 50

def buyer_id_argument(value):
    if type(value) not in (int, str) or (isinstance(value, str) and not value.strip().isdecimal()):
        raise ValueError('采购人或分页参数无效')
    try:
        parsed = int(value)
    except ValueError:
        raise ValueError('采购人或分页参数无效') from None
    if not 0 < parsed < 2**63:
        raise ValueError('采购人或分页参数无效')
    return parsed

def save_buyer(data):
    """关注一个采购人，或切换已有采购人的关注状态。"""
    with connect() as db:
        if 'id' in data:
            bid = buyer_id_argument(data['id'])
            followed = data.get('followed')
            if not isinstance(followed, bool):
                raise ValueError('关注状态无效')
            changed = db.execute('UPDATE buyers SET followed=? WHERE id=?', (int(followed), bid))
            if not changed.rowcount:
                raise ValueError('采购人不存在')
        else:
            name = buyer_name(data.get('name'))
            if not name:
                raise ValueError('请填写采购人名称')
            bid = get_buyer_id(db, name)
            db.execute('UPDATE buyers SET followed=1 WHERE id=?', (bid,))
    return bid

def buyer_projects(buyer, before=None):
    bid = buyer_id_argument(buyer)
    fields, args = 'p.buyer_id=?', [bid]
    if before is not None:
        fields += ' AND p.id<?'
        args.append(buyer_id_argument(before))
    with connect() as db:
        if not db.execute('SELECT 1 FROM buyers WHERE id=?', (bid,)).fetchone():
            return None
        rows = db.execute(f'{PROJECT_VIEW} WHERE {fields} ORDER BY p.id DESC LIMIT ?',
                          (*args, BUYER_PAGE_SIZE + 1)).fetchall()
    projects = [dict(row) for row in rows[:BUYER_PAGE_SIZE]]
    next_before = projects[-1]['id'] if len(rows) > BUYER_PAGE_SIZE else None
    return {'projects': projects, 'next_before': next_before}

def state():
    with connect() as db:
        return {'topics':[dict(x) for x in db.execute('SELECT * FROM topics ORDER BY id DESC')],
            'projects':[dict(x) for x in db.execute(
                PROJECT_VIEW + " ORDER BY COALESCE(NULLIF(p.first_seen_at,''),p.fetched_at) DESC,p.id DESC LIMIT 2000")],
            'buyers':[dict(row, followed=bool(row['followed'])) for row in db.execute(
                '''SELECT b.id,b.name,b.followed,COUNT(p.id) AS project_count FROM buyers b
                   LEFT JOIN projects p ON p.buyer_id=b.id GROUP BY b.id ORDER BY b.followed DESC,b.id DESC''')],
            'matches':[dict(x) for x in db.execute('SELECT * FROM matches')],
            'runs':[dict(x) for x in db.execute('SELECT * FROM runs ORDER BY id DESC LIMIT 50')],
            'workflows':[dict(x) for x in db.execute('SELECT * FROM workflows ORDER BY id')],
            'workflow_runs':[dict(x) for x in db.execute('SELECT * FROM workflow_runs ORDER BY id DESC LIMIT 30')],
            'config':config()}

def save_topic(data):
    values = [str(data.get(k,'')).strip() for k in ['name','keywords','excludes','region']]
    if not values[0] or not words(values[1]) or any(len(v)>1000 for v in values):
        raise ValueError('请填写主题名称、至少一个关键词；字段不得超过1000字。')
    with connect() as db:
        if data.get('id'):
            tid = int(data['id'])
            if not db.execute('SELECT id FROM topics WHERE id=?',(tid,)).fetchone():
                raise ValueError('主题不存在')
            db.execute('UPDATE topics SET name=?,keywords=?,excludes=?,region=? WHERE id=?',values+[tid])
            db.execute('DELETE FROM matches WHERE topic_id=?',(tid,))
        else:
            tid = db.execute('INSERT INTO topics(name,keywords,excludes,region,created_at) VALUES(?,?,?,?,?)', values+[now()]).lastrowid
        topic = dict(zip(['name','keywords','excludes','region'],values))
        for p in db.execute('SELECT id,title,content FROM projects').fetchall():
            match = evaluate(topic,p['title'],p['content'])
            if match:
                db.execute('INSERT OR REPLACE INTO matches VALUES(?,?,?,?)',(tid,p['id'],match[1],match[0]))
    return tid

def update_project(data):
    allowed = {'favorite','followup','notes','deadline','amount','stage'}
    fields = {k:v for k,v in data.items() if k in allowed}
    if 'favorite' in fields:
        fields['favorite'] = int(bool(fields['favorite']))
    if 'followup' in fields and fields['followup'] not in ['待研究','准备参与','已参与','不感兴趣']:
        raise ValueError('无效跟进状态')
    if 'stage' in fields and fields['stage'] not in ['待核实','招标公告','采购意向','更正公告','中标结果','终止公告','合同公告']:
        raise ValueError('无效公告阶段')
    if 'deadline' in fields and fields['deadline']:
        try:
            datetime.strptime(fields['deadline'],'%Y-%m-%dT%H:%M')
        except (ValueError,TypeError):
            raise ValueError('截止时间格式错误') from None
    for key in ['notes','amount']:
        if key in fields and (not isinstance(fields[key],str) or len(fields[key])>5000):
            raise ValueError('备注或金额格式错误')
    manual_buyer = 'buyer_name' in data
    name = buyer_name(data['buyer_name']) if manual_buyer else ''
    if fields or manual_buyer:
        pid = int(data['id'])
        with connect() as db:
            if manual_buyer:
                db.execute('BEGIN IMMEDIATE')
                if not db.execute('SELECT 1 FROM projects WHERE id=?', (pid,)).fetchone():
                    raise ValueError('项目不存在')
                fields['buyer_id'] = get_buyer_id(db, name) if name else None
                fields['buyer_source'] = 'manual'
            db.execute('UPDATE projects SET '+','.join(k+'=?' for k in fields)+' WHERE id=?', list(fields.values())+[pid])

def export_csv(topic_id=None, run_id=None):
    """导出线索为CSV。topic_id 为空时导出全部；否则只导出该主题命中的项目。
    run_id 用于导出一轮工作流新增的线索（该轮没有新增时导出只有表头的空表）。"""
    data = state()
    topic_names = {t['id']: t['name'] for t in data['topics']}
    if topic_id is not None and topic_id not in topic_names:
        raise ValueError('主题不存在')
    hits = {}
    for m in data['matches']:
        name = topic_names.get(m['topic_id'])
        if name:
            hits.setdefault(m['project_id'], set()).add(name)
    rows = data['projects']
    if topic_id is not None:
        keep = {m['project_id'] for m in data['matches'] if m['topic_id'] == topic_id}
        rows = [p for p in rows if p['id'] in keep]
    if run_id is not None:
        with connect() as db:
            row = db.execute('SELECT new_ids FROM workflow_runs WHERE id=?', (int(run_id),)).fetchone()
            if row is None:
                raise ValueError('运行记录不存在')
            keep = [int(x) for x in str(row['new_ids'] or '').split(',') if x.strip().isdigit()]
            # 直接按 id 取，避开 state() 的 2000 条上限，保证导出的就是这一轮的完整新增
            rows = [dict(x) for x in db.execute(
                'SELECT * FROM projects WHERE id IN (%s) ORDER BY id' % ','.join('?' * len(keep)), keep)] if keep else []
    out = io.StringIO(newline='')
    writer = csv.writer(out)
    writer.writerow(['项目名称','来源网站','原文链接','预算（人工核实）','截止时间（北京时间）','公告阶段','跟进','备注','来源渠道','形态','命中主题'])
    for p in rows:
        cells = [p['title'],p['source'],p['url'],p['amount'],p['deadline'],p['stage'],p['followup'],p['notes'],
                 ORIGIN_LABELS.get(p.get('origin') or ('demo' if p['demo'] else 'search'),'未知'),
                 '疑似公告' if p.get('is_notice',1) else '疑似非公告','；'.join(sorted(hits.get(p['id'],()))) or '未归入主题']
        writer.writerow(["'"+v if str(v).lstrip().startswith(('=','+','-','@')) else v for v in cells])
    return '\ufeff'+out.getvalue()

def reclassify():
    """按当前规则重算全部项目的形态标记，返回被标为「疑似非公告」的条数。"""
    with connect() as db:
        rows = db.execute('SELECT id,title,url,content,content_kind FROM projects').fetchall()
        flagged = 0
        for r in rows:
            flag = is_notice(r['title'], r['url'])
            kind = notice_kind(r['title'], flag, r['url'])
            db.execute('''UPDATE projects SET is_notice=?,notice_kind=?,official_deadline=?,
                          official_deadline_at=?,deadline_evidence=? WHERE id=?''',
                       (flag, kind, *deadline_fields(kind, r['content'], r['content_kind']), r['id']))
            flagged += (flag == 0)
        return flagged

def seed():
    path = ROOT / 'demo.json'
    with connect() as db:
        for p in json.loads(path.read_text(encoding='utf-8')):
            seen = '2026-11-02T08:00:00+00:00'
            db.execute('''INSERT OR IGNORE INTO projects(url,title,content,source,fetched_at,published,amount,
                          deadline,stage,demo,origin,content_kind,first_seen_at,last_seen_at,notice_kind)
                          VALUES(?,?,?,?,?,?,?,?,?,1,'demo','search',?,?,?)''',
                (p['url'],p['title'],p['content'],urllib.parse.urlsplit(p['url']).hostname,seen,p['published'],
                 p['amount'],p['deadline'],p['stage'],seen,seen,notice_kind(p['title'],1)))
        topics = [dict(t) for t in db.execute('SELECT * FROM topics')]
    for t in topics:
        save_topic(t)
