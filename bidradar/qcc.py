"""企查查招投标搜索：一次调用只请求一页，不重试、不自动翻页。"""

import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request


ENDPOINT = 'https://api.qichacha.com/TenderCheck/GetList'


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # AppKey 在查询参数中，不能跟随重定向把它带到其他地址。
        return None


def _open(request):
    return urllib.request.build_opener(_NoRedirect()).open(request, timeout=30)


def search(keyword, app_key, secret_key, *, area_code='', msg_type='', page_index=1):
    keyword = str(keyword or '').strip()
    area_code = str(area_code or '').strip()
    msg_type = str(msg_type or '').strip()
    if not keyword or any(len(part) > 100 for part in keyword.split()) or len(keyword) > 200:
        raise ValueError('请填写搜索关键词，单个关键词最多 100 字。')
    if area_code and not re.fullmatch(r'\d{6}', area_code):
        raise ValueError('请选择有效的省份或城市。')
    if msg_type not in ('', '3', '4'):
        raise ValueError('公告类型不正确。')
    if isinstance(page_index, bool) or not isinstance(page_index, int) or not 1 <= page_index <= 100:
        raise ValueError('页码必须在 1 到 100 之间。')
    if not app_key or not secret_key:
        raise ValueError('请先在标讯雷达配置企查查 AppKey 和 SecretKey。')

    timestamp = str(int(time.time()))
    signature = hashlib.md5((app_key + timestamp + secret_key).encode('utf-8')).hexdigest().upper()
    params = {'key': app_key, 'keyword': keyword, 'pageIndex': page_index, 'pageSize': 20}
    if area_code:
        params['areaCode'] = area_code
    if msg_type:
        params['msgType'] = msg_type
    request = urllib.request.Request(
        ENDPOINT + '?' + urllib.parse.urlencode(params),
        headers={'Token': signature, 'Timespan': timestamp, 'Accept': 'application/json'},
    )
    try:
        with _open(request) as response:
            raw = response.read(2_000_001)
        if len(raw) > 2_000_000:
            raise ValueError('企查查返回的数据过大。')
        payload = json.loads(raw)
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, UnicodeDecodeError):
        # 不回显异常或请求地址：地址内包含 AppKey。
        raise ValueError('无法连接企查查，请检查网络和接口开通状态。') from None
    if not isinstance(payload, dict):
        raise ValueError('企查查返回格式不正确。')
    status = str(payload.get('Status', ''))
    if status != '200':
        safe_status = status if re.fullmatch(r'\d{1,5}', status) else '未知'
        raise ValueError(f'企查查查询失败（状态码 {safe_status}），请检查凭证、额度及接口权限。')
    result = payload.get('Result')
    if not isinstance(result, dict):
        raise ValueError('企查查返回格式不正确。')
    items = result.get('Data')
    if items is None and str(result.get('VerifyResult')) == '0':
        items = []
    if not isinstance(items, list):
        raise ValueError('企查查返回格式不正确。')
    paging = payload.get('Paging') if isinstance(payload.get('Paging'), dict) else {}
    try:
        total = max(0, int(paging.get('TotalRecords', 0)))
    except (ValueError, TypeError):
        total = 0
    return {'items': items[:20], 'total': total, 'page': page_index}


def normalize(item):
    """只保留展示和匹配所需字段；采购人联系方式不自动导入。"""
    if not isinstance(item, dict):
        return None
    def text(key, limit=500):
        value = item.get(key)
        return str(value or '').strip()[:limit] if not isinstance(value, (dict, list)) else ''
    buyers = item.get('BidInviUnitList')
    names = [str(b.get('Name') or '').strip()[:160] for b in buyers if isinstance(b, dict)] if isinstance(buyers, list) else []
    progress = item.get('BidProgressList')
    stages = [str(s).strip()[:50] for s in progress if isinstance(s, str)] if isinstance(progress, list) else []
    fields = {
        'qcc_id': text('Id', 100), 'project_no': text('ProjectNo'),
        'budget': text('BudgetAmt', 100), 'bid_end_date': text('BidEndDate', 100),
        'buyer': '、'.join(name for name in names if name)[:500],
    }
    lines = [f'{label}：{value}' for label, value in [
        ('项目编号', fields['project_no']), ('项目分类', text('ChannelName')),
        ('地区', ' '.join(filter(None, [text('Province', 50), text('City', 50)]))),
        ('行业', text('IndustryDesc')), ('采购单位', fields['buyer']),
        ('预算（企查查提供，待核实）', fields['budget']),
        ('投标截止（企查查提供，待核实）', fields['bid_end_date']),
        ('招标进度', '、'.join(stages)),
    ] if value]
    return {**fields, 'title': text('Title', 500), 'url': text('ContentUrl', 5000),
            'published_date': text('PublishDate', 100), 'content': '\n'.join(lines)}
