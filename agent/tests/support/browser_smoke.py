"""Optional Chrome smoke check against an explicitly isolated local stack."""
import asyncio
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

import httpx
import websockets


async def check():
    base = os.environ.get("AGENT_BROWSER_SMOKE_URL", "")
    if os.environ.get("AGENT_BROWSER_SMOKE_ISOLATED") != "1" or not base.startswith("http://127.0.0.1:"):
        raise RuntimeError("Browser smoke check requires an explicitly isolated loopback stack")
    browser = Path(os.environ.get("AGENT_BROWSER_PATH", r"C:\Program Files\Google\Chrome\Application\chrome.exe"))
    if not browser.is_file():
        raise RuntimeError("Set AGENT_BROWSER_PATH to a local Chrome executable")
    data_dir = Path(__file__).resolve().parents[2] / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="browser-smoke-", dir=data_dir) as profile:
        if not Path(profile).resolve().is_relative_to(data_dir.resolve()):
            raise RuntimeError("Browser profile is outside the designated test directory")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        process = subprocess.Popen([str(browser), "--headless=new", "--disable-gpu", "--no-first-run",
            "--disable-background-networking", f"--remote-debugging-port={port}", f"--user-data-dir={profile}",
            "--window-size=1440,1000", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            with httpx.Client(trust_env=False, timeout=2) as client:
                for _ in range(100):
                    try:
                        pages = client.get(f"http://127.0.0.1:{port}/json").json()
                        page = next(item for item in pages if item.get("type") == "page")
                        break
                    except (httpx.HTTPError, StopIteration):
                        await asyncio.sleep(0.1)
                else:
                    raise RuntimeError("Chrome did not start")
            async with websockets.connect(page["webSocketDebuggerUrl"], max_size=10_000_000) as ws:
                sequence = 0

                async def command(method, params=None):
                    nonlocal sequence
                    sequence += 1
                    request_id = sequence
                    await ws.send(json.dumps({"id": request_id, "method": method, "params": params or {}}))
                    while True:
                        result = json.loads(await ws.recv())
                        if result.get("id") == request_id:
                            if "error" in result:
                                raise RuntimeError(result["error"])
                            return result.get("result", {})

                async def evaluate(expression):
                    result = await command("Runtime.evaluate", {"expression": expression, "returnByValue": True})
                    if result.get("exceptionDetails"):
                        raise RuntimeError(result["exceptionDetails"])
                    return result.get("result", {}).get("value")

                async def wait_for(expression, label):
                    for _ in range(150):
                        if await evaluate(expression):
                            return
                        await asyncio.sleep(0.1)
                    location = await evaluate("location.pathname")
                    visible = await evaluate("document.body.innerText.slice(0, 500)")
                    raise RuntimeError(f"Timed out waiting for {label}; path={location}; visible={visible}")

                await command("Page.navigate", {"url": base + "/agent"})
                await wait_for("!!document.querySelector('input[autocomplete=username]')", "login page")
                values = json.dumps([os.environ["AGENT_BROWSER_SMOKE_USER"], os.environ["AGENT_BROWSER_SMOKE_PASSWORD"]])
                await evaluate(f"""(() => {{
                    const values = {values};
                    for (const [i, selector] of ['input[autocomplete=username]', 'input[autocomplete=current-password]'].entries()) {{
                        const input = document.querySelector(selector);
                        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, values[i]);
                        input.dispatchEvent(new Event('input', {{bubbles:true}}));
                    }}
                    document.querySelector('form').requestSubmit();
                }})()""")
                await wait_for("location.pathname === '/agent' && document.body.innerText.includes('招商 Agent')", "agent workspace")
                text = await evaluate("document.body.innerText")
                assert "招商 Agent" in text
                assert "分别计算楼宇匹配、租赁意向与数据可信度" in text
                assert "创建线索与跟进任务" not in text
                await evaluate("Array.from(document.querySelectorAll('button')).find(b => b.innerText.trim() === '待确认').click()")
                await wait_for("document.body.innerText.includes('历史任务 · 待确认')", "pending review filter")
                screenshot = await command("Page.captureScreenshot", {"format": "png"})
                (data_dir / "browser-smoke.png").write_bytes(base64.b64decode(screenshot["data"]))
                await evaluate("""(() => {
                    const input = document.querySelector('textarea');
                    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, '【自动化测试】查找南京企业');
                    input.dispatchEvent(new Event('input', {bubbles:true}));
                })()""")
                await wait_for("!!Array.from(document.querySelectorAll('button')).find(b => b.innerText.includes('发起获客任务') && !b.disabled)", "send button")
                await evaluate("Array.from(document.querySelectorAll('button')).find(b => b.innerText.includes('发起获客任务')).click()")
                await wait_for("document.body.innerText.includes('DEEPSEEK_API_KEY')", "missing model key error")
                await command("Page.navigate", {"url": base + "/collector"})
                await wait_for("Array.from(document.querySelectorAll('.el-tabs__item')).some(x => x.innerText.includes('监测任务'))", "monitor tab")
                await evaluate("Array.from(document.querySelectorAll('.el-tabs__item')).find(x => x.innerText.includes('监测任务')).click()")
                await wait_for("document.body.innerText.includes('创建暂停任务') && document.body.innerText.includes('连续失败三次会自动暂停')", "monitor management")
                screenshot = await command("Page.captureScreenshot", {"format": "png"})
                (data_dir / "monitor-smoke.png").write_bytes(base64.b64decode(screenshot["data"]))
                print("PASS: browser login -> agent workspace -> missing-key error -> monitor management")
        finally:
            process.terminate()
            process.wait(timeout=10)
            time.sleep(0.5)


if __name__ == "__main__":
    asyncio.run(check())
