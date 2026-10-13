// Full-page screenshot at an exact viewport width using Chrome DevTools Protocol.
// Usage: node cdp-shot.js <width> <out.png>
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const WIDTH = parseInt(process.argv[2] || '375', 10);
const OUT = process.argv[3] || 'screenshots/mobile.png';
const PORT = 9333;
const URL_PAGE = 'http://127.0.0.1:8123/index.html';

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'shot-'));
const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
  '--remote-debugging-port=' + PORT,
  '--user-data-dir=' + profile,
  'about:blank'
], { stdio: 'ignore', windowsHide: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function getTarget() {
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(URL_PAGE)}`, { method: 'PUT' });
      if (r.ok) return await r.json();
    } catch (e) { /* not up yet */ }
    await sleep(200);
  }
  throw new Error('Chrome DevTools endpoint did not start');
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    const pending = new Map();
    ws.onopen = () => resolve({
      send(method, params = {}) {
        const msgId = ++id;
        return new Promise((res, rej) => {
          pending.set(msgId, { res, rej });
          ws.send(JSON.stringify({ id: msgId, method, params }));
        });
      },
      close() { ws.close(); }
    });
    ws.onerror = e => reject(new Error('ws error'));
    ws.onmessage = ev => {
      const msg = JSON.parse(ev.data);
      if (msg.id && pending.has(msg.id)) {
        const { res, rej } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
      }
    };
  });
}

(async () => {
  const target = await getTarget();
  const cdp = await connect(target.webSocketDebuggerUrl);

  await cdp.send('Page.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: WIDTH, height: 900, deviceScaleFactor: 1, mobile: WIDTH < 600
  });
  await cdp.send('Page.navigate', { url: URL_PAGE });
  await sleep(1500);

  const { result } = await cdp.send('Runtime.evaluate', {
    expression: '({w: document.documentElement.clientWidth, h: document.documentElement.scrollHeight, sw: document.documentElement.scrollWidth})',
    returnByValue: true
  });
  const { w, h, sw } = result.value;
  console.log(`layout: width=${w} height=${h} scrollWidth=${sw}`);

  const shot = await cdp.send('Page.captureScreenshot', {
    format: 'png',
    captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: w, height: h, scale: 1 }
  });
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
  console.log('wrote ' + OUT);

  cdp.close();
  chrome.kill();
  process.exit(0);
})().catch(err => {
  console.error(err);
  try { chrome.kill(); } catch (e) {}
  process.exit(1);
});
