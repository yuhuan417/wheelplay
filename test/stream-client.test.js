import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replaceAll('export function', 'function').replaceAll('export const', 'const') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replaceAll('export function', 'function').replaceAll('export const', 'const');
function fixture({ devicePixelRatio = 1, fullscreen = 'supported' } = {}) {
  const elements = new Map(), timers = new Map(), raf = new Map(), sockets = [];
  const preferences = new Map();
  const documentEvents = {}, windowEvents = {}, fullscreenCalls = [];
  let nextTimer = 0, nextFrame = 0, rectCalls = 0;
  let currentRect = { left: 0, top: 0, width: 1280, height: 720 };
  const element = id => {
    const classes = new Set();
    if (!elements.has(id)) elements.set(id, { hidden: false, value: '123456', handlers: {}, naturalWidth: 1280, naturalHeight: 720,
      checked: false,
      classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name) },
      removeAttribute() {}, setAttribute() {}, setPointerCapture() {},
      getBoundingClientRect: () => { rectCalls++; return currentRect; },
      addEventListener(name, fn) { this.handlers[name] = fn; } });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; this.onclose(); }
  }
  const document = { getElementById: element, fullscreenElement: null, documentElement: {},
    addEventListener(name, fn) { documentEvents[name] = fn; },
    exitFullscreen() {
      fullscreenCalls.push('exit'); this.fullscreenElement = null;
      documentEvents.fullscreenchange(); return Promise.resolve();
    },
  };
  const enterFullscreen = () => { document.fullscreenElement = document.documentElement; documentEvents.fullscreenchange(); };
  if (fullscreen !== 'unsupported') document.documentElement.requestFullscreen = () => {
    fullscreenCalls.push('request');
    if (typeof fullscreen === 'function') return fullscreen(enterFullscreen);
    if (fullscreen === 'denied') return Promise.reject(new Error('fullscreen denied'));
    enterFullscreen(); return Promise.resolve();
  };
  const context = { document, window: { devicePixelRatio,
    get innerWidth() { return currentRect.width; },
    get innerHeight() { return currentRect.height; },
    addEventListener(name, fn) { windowEvents[name] = fn; },
    localStorage: {
      getItem(key) { return preferences.get(key) ?? null; },
      setItem(key, value) { preferences.set(key, value); },
      removeItem(key) { preferences.delete(key); },
    },
  },
    location: { host: 'server:8080' },
    Date, WebSocket: Socket,
    performance: { now: () => 0 },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { const frame = ++nextFrame; raf.set(frame, fn); return frame; },
    cancelAnimationFrame(frame) { raf.delete(frame); },
  };
  vm.runInNewContext(source, context);
  return { element, sockets, timers, preferences, raf, document, windowEvents, fullscreenCalls,
    get rectCalls() { return rectCalls; },
    setRect(rect) { currentRect = rect; },
    connect() {
      element('connect-form').handlers.submit({ preventDefault() {} });
      const ws = sockets.at(-1); ws.readyState = 1; ws.onopen(); return ws;
    },
    // A server status message with streaming=true makes the touch overlay live; WebCodecs
    // decoding itself is covered by the browser and Kotlin tests, not this fixture.
    live(ws) { ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true, stage: 'CarPlay' }) }); },
    pointer(type, x = 100, surface = 'touch-surface') { element(surface).handlers[type]({ pointerId: 1, pointerType: 'touch', clientX: x, clientY: 100, preventDefault() {} }); },
    flushMoves() { const callbacks = [...raf.values()]; raf.clear(); callbacks.forEach(fn => fn()); },
    retry() {
      const [id, timer] = [...timers].find(([, t]) => t.delay === 10);
      timers.delete(id); timer.fn();
    },
  };
}

test('the overlay forwards a complete drag independently of the video surface', async () => {
  const f = fixture(), ws = await f.connect(); f.live(ws);
  f.pointer('pointerdown', 100, 'touch-surface');
  f.pointer('pointermove', 200, 'touch-surface'); f.flushMoves();
  f.pointer('pointerup', 200, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, true, false]);
});

test('first pointermove in an open VSync window sends immediately on the leading edge while same-frame moves coalesce', async () => {
  const f = fixture(), ws = await f.connect(); f.live(ws);
  f.pointer('pointerdown', 128, 'touch-surface');
  f.flushMoves(); // VSync window after pointerdown closes with no pending move.
  f.pointer('pointermove', 256, 'touch-surface');
  const touchesAfterLeadingMove = ws.sent.filter(m => m.type === 'touch');
  assert.equal(touchesAfterLeadingMove.length, 2, 'First move in an open VSync window must send immediately');
  assert.equal(touchesAfterLeadingMove[1].contacts[0].x, 0.2);

  // Subsequent moves in the same VSync window coalesce until the next rAF.
  f.pointer('pointermove', 384, 'touch-surface');
  f.pointer('pointermove', 512, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  f.flushMoves();
  const touchesAfterRaf = ws.sent.filter(m => m.type === 'touch');
  assert.equal(touchesAfterRaf.length, 3);
  assert.equal(touchesAfterRaf[2].contacts[0].x, 0.4);
});

test('the optional top bar persists in storage, stays visible while waiting for iPhone, hides when live, and returns to setup on host-ui', async () => {
  const f = fixture();
  f.element('hide-toolbar').checked = true;
  f.element('hide-toolbar').handlers.change();
  assert.equal(f.preferences.get('wheelplay.hideToolbar'), 'true');
  const ws = await f.connect();
  assert.equal(f.element('topbar').hidden, true);
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: false }) });
  assert.equal(f.element('topbar').hidden, false, 'Top bar must remain visible while waiting for iPhone');
  f.live(ws);
  assert.equal(f.element('topbar').hidden, true, 'Top bar hides once CarPlay frame is live');
  ws.onmessage({ data: JSON.stringify({ type: 'host-ui' }) });
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('pairing').hidden, false);
  assert.equal(f.element('display').hidden, true);
});

test('normal launch leaves fullscreen and the top bar unchanged', async () => {
  const f = fixture(); await f.connect();
  assert.deepEqual(f.fullscreenCalls, []);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
});

test('immersive launch requests fullscreen in the submit gesture and hides the bar after connecting', async () => {
  const f = fixture(); f.element('fullscreen-on-start').checked = true;
  const ws = f.connect();
  assert.deepEqual(f.fullscreenCalls, ['request']);
  assert.equal(ws.readyState, 1);
  assert.equal(f.document.fullscreenElement, f.document.documentElement);
  assert.equal(f.element('topbar').hidden, true);
  await f.document.exitFullscreen();
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('display').hidden, false, 'Leaving fullscreen keeps the stream connected');
});

test('disconnect exits launch-owned fullscreen and restores the launch interface', async () => {
  const f = fixture(); f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('pairing').hidden, false);
});

test('unavailable or rejected fullscreen falls back to hiding the bar without preventing launch', async () => {
  for (const fullscreen of ['unsupported', 'denied']) {
    const f = fixture({ fullscreen }); f.element('fullscreen-on-start').checked = true;
    await f.connect();
    assert.equal(f.document.fullscreenElement, null);
    assert.equal(f.element('topbar').hidden, true);
    assert.equal(f.element('display').hidden, false);
    f.element('disconnect').handlers.click();
    assert.equal(f.element('topbar').hidden, false);
    assert.equal(f.fullscreenCalls.includes('exit'), false);
  }
});

test('a fullscreen request that completes after disconnect is released', async () => {
  let complete;
  const f = fixture({ fullscreen: enter => new Promise(resolve => { complete = () => { enter(); resolve(); }; }) });
  f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  complete(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
});

test('disconnect leaves fullscreen that was already active before launch intact', async () => {
  const f = fixture(); f.document.fullscreenElement = f.document.documentElement;
  f.element('fullscreen-on-start').checked = true;
  await f.connect(); f.element('disconnect').handlers.click();
  assert.deepEqual(f.fullscreenCalls, []);
  assert.equal(f.document.fullscreenElement, f.document.documentElement);
});

test('pending movement is cancelled at up before another down', async () => {
  const f = fixture(), ws = await f.connect(); f.live(ws);
  f.pointer('pointerdown'); f.pointer('pointermove', 200); f.pointer('pointerup'); f.pointer('pointerdown', 300);
  const before = ws.sent.length; f.flushMoves();
  assert.equal(ws.sent.length, before);
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, false, true]);
});

test('phone connection requires an open browser connection and reports the server result', async () => {
  const f = fixture();
  f.element('phone-connect').handlers.click();
  assert.equal(f.sockets.length, 0);
  assert.match(f.element('phone-connect-message').textContent, /重新连接/);
  const ws = await f.connect();
  f.element('phone-connect').handlers.click();
  assert.equal(ws.sent.filter(m => m.type === 'phone-connect').length, 1);
  assert.equal(f.element('phone-connect').disabled, true);
  ws.onmessage({ data: JSON.stringify({ type: 'phone-connect-result', message: '请在 Android 上完成授权' }) });
  assert.equal(f.element('phone-connect').disabled, false);
  assert.equal(f.element('phone-connect-message').textContent, '请在 Android 上完成授权');
});

test('phone connection survives congestion and is sent once after recovery', async () => {
  const f = fixture(), ws = await f.connect();
  ws.bufferedAmount = 8193;
  f.element('phone-connect').handlers.click();
  assert.equal(ws.sent.some(m => m.type === 'phone-connect'), false);
  assert.equal(f.element('phone-connect').disabled, true);
  ws.bufferedAmount = 0;
  f.retry();
  assert.equal(ws.sent.filter(m => m.type === 'phone-connect').length, 1);
});


test('interrupted display reconnects to the same stream without pairing', async () => {
  const f = fixture();
  const old = f.connect(); old.close();
  const retry = [...f.timers.values()].find(t => t.delay === 1000);
  assert.ok(retry); await retry.fn();
  assert.equal(f.sockets.length, 2);
  assert.equal(f.sockets[1].url, 'ws://server:8080/stream');
});

test('touch drags reuse cached bounding rect and invalidate on resize or fullscreen change', async () => {
  const f = fixture(), ws = await f.connect(); f.live(ws);
  const beforeDrag = f.rectCalls;
  f.pointer('pointerdown', 100, 'touch-surface');
  for (let x = 110; x <= 300; x += 10) f.pointer('pointermove', x, 'touch-surface');
  f.flushMoves();
  f.pointer('pointerup', 300, 'touch-surface');
  assert.equal(f.rectCalls - beforeDrag, 1, 'Drag must query getBoundingClientRect only once');

  f.setRect({ left: 0, top: 0, width: 640, height: 360 });
  f.windowEvents.resize();
  const beforeSecondDrag = f.rectCalls;
  f.pointer('pointerdown', 320, 'touch-surface');
  f.pointer('pointerup', 320, 'touch-surface');
  assert.equal(f.rectCalls - beforeSecondDrag, 1, 'Resize must invalidate cached bounding rect');
});

test('high-DPR car displays cap reported viewport long edge at 1280 while preserving aspect ratio', async () => {
  const f = fixture({ devicePixelRatio: 2 }), ws = await f.connect();
  f.setRect({ left: 0, top: 0, width: 1200, height: 880 });
  f.windowEvents.resize();
  const viewportTimer = [...f.timers.values()].find(t => t.delay === 180);
  assert.ok(viewportTimer);
  viewportTimer.fn();
  assert.deepEqual(ws.sent.find(m => m.type === 'viewport'), { type: 'viewport', width: 1280, height: 938 });
});

