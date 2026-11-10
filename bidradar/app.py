"""Single-user localhost application. Run: python app.py."""
import argparse
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parent
if (ROOT / '.env').exists():
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            k,v = line.split('=',1)
            os.environ.setdefault(k.strip(),v.strip().strip('"').strip("'"))
import core

class Handler(BaseHTTPRequestHandler):
    def log_message(self,fmt,*args):
        # Log paths/status only; never request payloads or credentials.
        super().log_message(fmt,*args)

    def reply(self,status,data,mime='application/json; charset=utf-8'):
        payload = data if isinstance(data,bytes) else (json.dumps(data,ensure_ascii=False).encode() if 'json' in mime else data.encode())
        self.send_response(status)
        self.send_header('Content-Type',mime)
        self.send_header('Content-Length',str(len(payload)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        # script-src 必须包含 'unsafe-eval'：前端使用 Vue 运行时编译器（vue.global.prod.js）
        # 把模板字符串编译成 render 函数，内部走 Function(code)，没有 unsafe-eval 会被浏览器拒绝，
        # 结果是 #app 永远为空（白屏）。脚本来源仍被 'self' 限制为同源本地文件，页面无 v-html 注入面。
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
        self.end_headers()
        self.wfile.write(payload)

    def safe_request(self):
        host = self.headers.get('Host','')
        allowed = {f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'}
        if host not in allowed:
            self.reply(403,{'error':'仅允许本机访问'})
            return False
        origin = self.headers.get('Origin')
        if origin and origin not in {'http://'+h for h in allowed}:
            self.reply(403,{'error':'禁止跨站请求'})
            return False
        return True

    def do_GET(self):
        if not self.safe_request():
            return
        path = urlsplit(self.path).path
        if path == '/api/state':
            self.reply(200,core.state())
        elif path == '/api/buyers/projects':
            qs = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
            buyer = (qs.get('buyer') or [None])[0]
            before = (qs.get('before') or [None])[0]
            try:
                result = core.buyer_projects(buyer, before)
            except ValueError as e:
                self.reply(400, {'error': str(e)})
            else:
                if result is None:
                    self.reply(404, {'error': '采购人不存在'})
                else:
                    self.reply(200, result)
        elif path == '/api/export':
            # ?topic=<id> 只导出该主题命中的线索；?run=<id> 只导出某轮工作流的新增；
            # 都不带则导出全部
            qs = parse_qs(urlsplit(self.path).query)
            run_raw = (qs.get('run') or [''])[0].strip()
            topic_raw = (qs.get('topic') or [''])[0].strip()
            if run_raw and not run_raw.isdigit():
                self.reply(400,{'error':'运行记录参数无效'})
                return
            if topic_raw and not topic_raw.isdigit():
                self.reply(400,{'error':'主题参数无效'})
                return
            try:
                if run_raw:
                    payload = core.export_csv(run_id=int(run_raw))
                elif topic_raw:
                    payload = core.export_csv(int(topic_raw))
                else:
                    payload = core.export_csv()
            except ValueError as e:
                self.reply(404,{'error':str(e)})
            else:
                self.reply(200,payload,'text/csv; charset=utf-8')
        elif path in ['/', '/app.js','/style.css']:
            name = {'/':'index.html','/app.js':'app.js','/style.css':'style.css'}[path]
            mime = {'/':'text/html; charset=utf-8','/app.js':'text/javascript; charset=utf-8','/style.css':'text/css; charset=utf-8'}[path]
            self.reply(200,(ROOT/'web'/name).read_bytes(),mime)
        elif path.startswith('/vendor/'):
            fname = path[len('/vendor/'):]
            # Only serve known static assets, no path traversal.
            if '/' in fname or fname in ('vue.global.prod.js','element-plus.js','element-plus.css','icons.js'):
                target = (ROOT/'web'/'vendor'/fname).resolve()
                if target.is_file() and str(target).startswith(str(ROOT/'web'/'vendor')):
                    mimes = {'.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8'}
                    mime = mimes.get(target.suffix,'application/octet-stream')
                    self.reply(200,target.read_bytes(),mime)
                    return
            self.reply(404,{'error':'文件不存在'})
        else:
            self.reply(404,{'error':'页面不存在'})

    def do_POST(self):
        if not self.safe_request():
            return
        try:
            if self.headers.get('Content-Type','').split(';')[0] != 'application/json':
                raise ValueError('请求必须为 JSON')
            length = int(self.headers.get('Content-Length','0'))
            if length < 1 or length > 32000:
                raise ValueError('请求大小不正确')
            data = json.loads(self.rfile.read(length))
            if not isinstance(data,dict):
                raise ValueError('请求格式不正确')
            path = urlsplit(self.path).path
            result = {'ok':True}
            searching = core.SEARCH_LOCK.locked() or core.WORKFLOW_LOCK.locked() or core.CRAWL_LOCK.locked()
            if path in ['/api/topics','/api/topics/delete'] and searching:
                raise ValueError('搜索或采集进行中，请结束后再修改主题。')
            if path in ['/api/workflows','/api/workflows/delete'] and core.WORKFLOW_LOCK.locked():
                raise ValueError('已有一轮工作流正在执行，请结束后再修改配置。')
            if path == '/api/topics':
                result['id'] = core.save_topic(data)
            elif path == '/api/topics/delete':
                with core.connect() as db:
                    db.execute('DELETE FROM topics WHERE id=?',(int(data['id']),))
            elif path == '/api/scan':
                result = core.scan(int(data['id']))
            elif path == '/api/scan/intents':
                result = core.scan(int(data['id']), intents=True)
            elif path == '/api/qcc/search':
                result = core.qcc_search(int(data['id']), data.get('keyword', ''),
                                         area_code=data.get('area_code', ''),
                                         msg_type=data.get('msg_type', ''),
                                         page_index=int(data.get('page_index', 1)))
            elif path == '/api/crawl':
                result = core.crawl_topic(int(data['id']))
            elif path == '/api/workflows':
                result['id'] = core.save_workflow(data)
            elif path == '/api/workflows/delete':
                core.delete_workflow(int(data['id']))
            elif path == '/api/workflows/run':
                result = core.run_workflow(int(data['id']) if data.get('id') else None,'manual')
            elif path == '/api/buyers':
                result['id'] = core.save_buyer(data)
            elif path == '/api/projects':
                core.update_project(data)
            elif path == '/api/fetch':
                core.fetch_page(int(data['id']))
            elif path == '/api/demo':
                core.seed()
            elif path == '/api/demo/delete':
                with core.connect() as db:
                    db.execute('DELETE FROM projects WHERE demo=1')
            elif path == '/api/settings':
                interval = int(data.get('interval_hours',0))
                if interval not in [0,6,12,24]:
                    raise ValueError('扫描间隔不正确')
                with core.connect() as db:
                    db.execute('INSERT OR REPLACE INTO settings VALUES(?,?)',('interval_hours',str(interval)))
                    db.execute('INSERT OR REPLACE INTO settings VALUES(?,?)',('official_only','true' if data.get('official_only') else 'false'))
            else:
                self.reply(404,{'error':'接口不存在'})
                return
            self.reply(200,result)
        except (ValueError,KeyError,TypeError) as e:
            self.reply(400,{'error':str(e)})
        except Exception:
            self.reply(500,{'error':'操作失败，请查看本地运行环境并重试。'})

def scheduler(stop):
    """每 30 秒检查一次到期的工作流；到期的顺序执行，一次只跑一轮。"""
    while not stop.wait(30):
        has_key = bool(os.environ.get('DEEPSEEK_API_KEY', '').strip())
        for wf in core.workflows():
            if stop.is_set():
                return
            if wf['channels'] == 'search' and not has_key:
                continue
            try:
                if core.workflow_due(wf):
                    core.run_workflow(wf['id'],'schedule')
            except (ValueError,RuntimeError):
                # 单个工作流失败（余额不足、锁冲突等）不影响其他工作流与下一轮
                pass

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=8765)
    args = parser.parse_args()
    core.init()
    with core.connect() as db:
        db.execute("UPDATE runs SET status='已中断',finished_at=? WHERE status='执行中'",(core.now(),))
        db.execute("UPDATE workflow_runs SET status='已中断',finished_at=? WHERE status='执行中'",(core.now(),))
    server = ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    stop = threading.Event()
    threading.Thread(target=scheduler,args=(stop,),daemon=True).start()
    print(f'BidRadar ready: http://127.0.0.1:{args.port}  (Ctrl+C to stop)',flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()

if __name__ == '__main__':
    main()
