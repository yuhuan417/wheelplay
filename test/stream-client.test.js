import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../common/src/main/assets/web/browser-audio.js', import.meta.url), 'utf8')
  .replaceAll('export function', 'function') + '\n' +
  readFileSync(new URL('../common/src/main/assets/web/app.js', import.meta.url), 'utf8')
    .replace(/^import .*browser-audio\.js';$/m, '').replaceAll('export function', 'function');
function fixture({ storedCanvas = null, storageDisabled = false, canvasAvailable = true,
  createImageBitmap = null, devicePixelRatio = 1,
  fullscreen = 'supported', codeDenied = false, resume = () => ({ token: 'session-token', name: 'Chrome' }) } = {}) {
  const elements = new Map(), timers = new Map(), raf = new Map(), sockets = [], peers = [], draws = [];
  const preferences = new Map(storedCanvas === null ? [] : [['wheelplay.canvasVideo', storedCanvas]]);
  const videoCallbacks = new Map();
  const documentEvents = {}, windowEvents = {}, fullscreenCalls = [], objectUrls = [];
  let nextTimer = 0, nextFrame = 0, rectCalls = 0;
  let currentRect = { left: 0, top: 0, width: 1280, height: 720 };
  const element = id => {
    const classes = new Set();
    if (!elements.has(id)) elements.set(id, { hidden: false, value: '123456', handlers: {}, naturalWidth: 1280, naturalHeight: 720,
      checked: false, videoWidth: 1280, videoHeight: 720, readyState: 2, currentTime: 0,
      getContext: () => canvasAvailable ? { drawImage: (...args) => draws.push(args) } : null,
      play: async () => {},
      requestVideoFrameCallback(fn) { const frame = ++nextFrame; videoCallbacks.set(frame, fn); return frame; },
      cancelVideoFrameCallback(frame) { videoCallbacks.delete(frame); },
      classList: { toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }, contains: name => classes.has(name) },
      removeAttribute() {}, setAttribute() {}, setPointerCapture() {},
      getBoundingClientRect: () => { rectCalls++; return currentRect; },
      addEventListener(name, fn) { this.handlers[name] = fn; } });
    return elements.get(id);
  };
  // The JPEG path double-buffers: exactly one of the two image elements holds a pending load.
  const pending = () => [element('screen'), element('screen-next')]
    .find(candidate => typeof candidate.onload === 'function');
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.readyState = 3; this.onclose(); }
  }
  class Peer {
    constructor() { this.iceGatheringState = 'complete'; this.localDescription = { sdp: 'answer' }; peers.push(this); }
    async setRemoteDescription() {}
    async createAnswer() { return { type: 'answer', sdp: 'a=setup:active\r\n' }; }
    async setLocalDescription() {}
    close() { this.closed = true; }
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
    addEventListener(name, fn) { windowEvents[name] = fn; },
    localStorage: {
      getItem(key) { if (storageDisabled) throw new Error('denied'); return preferences.get(key) ?? null; },
      setItem(key, value) { if (storageDisabled) throw new Error('denied'); preferences.set(key, value); },
      removeItem(key) { preferences.delete(key); },
    },
  },
    ...(createImageBitmap ? { createImageBitmap } : {}),
    location: { host: 'server:8080' },
    URL: { createObjectURL: blob => { objectUrls.push(blob); return 'blob:frame'; }, revokeObjectURL() {} },
    Date, WebSocket: Socket,
    RTCPeerConnection: Peer, performance: { now: () => 0 },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return 0; }, clearInterval() {},
    requestAnimationFrame(fn) { const frame = ++nextFrame; raf.set(frame, fn); return frame; },
    cancelAnimationFrame(frame) { raf.delete(frame); },
    fetch: async url => {
      if (url === '/pair/code') return codeDenied ? { ok: false, status: 401 } : { ok: true, json: async () => ({ token: 'session-token' }) };
      if (url === '/pair/remember') return { ok: true, json: async () => ({ id: 'd'.repeat(32), secret: 's'.repeat(32), name: 'Chrome' }) };
      if (url === '/pair/resume') return { ok: true, json: async () => resume() };
      return new Promise(() => {});
    },
  };
  vm.runInNewContext(source, context);
  return { element, pending, sockets, timers, peers, draws, preferences, raf, videoCallbacks, document, windowEvents, fullscreenCalls, objectUrls,
    get rectCalls() { return rectCalls; },
    setRect(rect) { currentRect = rect; },
    async connect() {
      element('connect-form').handlers.submit({ preventDefault() {} });
      await new Promise(resolve => setImmediate(resolve));
      const ws = sockets.at(-1); ws.readyState = 1; ws.onopen(); return ws;
    },
    // JPEG frames load into the idle buffer and swap in on load, so the pending handler moves
    // between the two image elements. `pending` names whichever one is currently loading.
    frame(ws) { ws.onmessage({ data: {} }); pending().onload(); },
    async offer(ws, id = 1) {
      ws.onmessage({ data: JSON.stringify({ type: 'rtc-offer', id, sdp: 'offer' }) });
      await new Promise(resolve => setImmediate(resolve));
      peers.at(-1).ontrack({ streams: [{}] });
      await new Promise(resolve => setImmediate(resolve));
    },
    pointer(type, x = 100, surface = 'screen') { element(surface).handlers[type]({ pointerId: 1, pointerType: 'touch', clientX: x, clientY: 100, preventDefault() {} }); },
    flushMoves() { const callbacks = [...raf.values()]; raf.clear(); callbacks.forEach(fn => fn()); },
    retry() {
      const [id, timer] = [...timers].find(([, t]) => t.delay === 10);
      timers.delete(id); timer.fn();
    },
  };
}

test('the overlay forwards a complete drag independently of the native video element', async () => {
  const f = fixture(), ws = await f.connect(); f.frame(ws);
  f.pointer('pointerdown', 100, 'touch-surface');
  f.pointer('pointermove', 200, 'touch-surface'); f.flushMoves();
  f.pointer('pointerup', 200, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, true, false]);
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
  f.frame(ws);
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
  const connecting = f.connect();
  assert.deepEqual(f.fullscreenCalls, ['request']);
  assert.equal(f.sockets.length, 0, 'Fullscreen must be requested before asynchronous pairing finishes');
  assert.equal(f.element('topbar').hidden, false);
  await connecting;
  assert.equal(f.document.fullscreenElement, f.document.documentElement);
  assert.equal(f.element('topbar').hidden, true);
  await f.document.exitFullscreen();
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('display').hidden, false, 'Leaving fullscreen keeps the stream connected');
});

test('disconnect exits launch-owned fullscreen and restores the pairing interface', async () => {
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

test('pairing failure exits launch-owned fullscreen without hiding the pairing interface', async () => {
  const f = fixture({ codeDenied: true }); f.element('fullscreen-on-start').checked = true;
  f.element('connect-form').handlers.submit({ preventDefault() {} });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.sockets.length, 0);
  assert.deepEqual(f.fullscreenCalls, ['request', 'exit']);
  assert.equal(f.document.fullscreenElement, null);
  assert.equal(f.element('topbar').hidden, false);
  assert.equal(f.element('pairing').hidden, false);
  assert.equal(f.element('connect').disabled, false);
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

test('actual image onload retries ACK and preserves down/up during browser congestion', async () => {
  const f = fixture(), ws = await f.connect();
  ws.bufferedAmount = 8192;
  f.frame(ws); f.pointer('pointerdown'); f.pointer('pointermove', 200); f.flushMoves(); f.pointer('pointerup', 200);
  assert.deepEqual(ws.sent.map(m => m.type), ['ping']);
  ws.bufferedAmount = 0; f.retry();
  assert.deepEqual(ws.sent.map(m => m.type), ['ping', 'ack', 'touch', 'touch', 'touch']);
  assert.deepEqual(ws.sent.slice(2).map(m => m.contacts[0].down), [true, true, false]);
});

test('disconnect drops pending ACK and old animation callbacks cannot send into a new gesture', async () => {
  const f = fixture(), old = await f.connect(); f.frame(old); f.pointer('pointerdown');
  f.pointer('pointermove', 200);
  old.bufferedAmount = 8192; f.frame(old);
  // Hand over a frame that has not finished loading, then resolve it after the socket is
  // replaced. Double buffering parks that handler on the idle image element.
  old.onmessage({ data: {} });
  const lateImageLoad = f.pending().onload;
  old.close();
  const current = await f.connect(); f.frame(current); f.pointer('pointerdown', 300);
  const before = current.sent.length;
  lateImageLoad(); f.flushMoves();
  assert.equal(current.sent.length, before);
  assert.equal([...f.timers.values()].some(t => t.delay === 10), false);
  assert.equal(old.sent.filter(m => m.type === 'ack').length, 1);
});

test('pending movement is cancelled at up before another down', async () => {
  const f = fixture(), ws = await f.connect(); f.frame(ws);
  f.pointer('pointerdown'); f.pointer('pointermove', 200); f.pointer('pointerup'); f.pointer('pointerdown', 300);
  const before = ws.sent.length; f.flushMoves();
  assert.equal(ws.sent.length, before);
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, false, true]);
});

test('Canvas preference defaults off, persists per browser, and tolerates unavailable storage', async () => {
  const defaults = fixture(); assert.equal(defaults.element('canvas-video').checked, false);
  const f = fixture({ storedCanvas: 'true' }); assert.equal(f.element('canvas-video').checked, true);
  f.element('canvas-video').checked = false; f.element('canvas-video').handlers.change();
  assert.equal(f.preferences.get('wheelplay.canvasVideo'), 'false');
  const denied = fixture({ storageDisabled: true });
  denied.element('canvas-video').checked = true; denied.element('canvas-video').handlers.change();
  assert.equal((await denied.connect()).readyState, 1);
});

test('default WebRTC presents native video without allocating a Canvas', async () => {
  const f = fixture(), ws = await f.connect(); await f.offer(ws);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video').classList.contains('offscreen-video'), false);
  assert.equal(f.element('video-canvas').hidden, true); assert.equal(f.draws.length, 0);
  assert.equal(ws.sent.at(-1).type, 'rtc-ready');
});

test('a recoverable WebRTC disconnect preserves video and touch and reports recovery', async () => {
  const f = fixture(), ws = await f.connect(); await f.offer(ws);
  const peer = f.peers.at(-1);
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true }) });
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('waiting').hidden, true);
  assert.match(f.element('status').textContent, /WebRTC.*等待恢复/);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  assert.equal(ws.sent.some(m => m.type === 'rtc-fallback'), false);
  peer.connectionState = 'connected'; peer.onconnectionstatechange();
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true }) });
  assert.doesNotMatch(f.element('status').textContent, /等待恢复/);
});

test('Canvas WebRTC handles touch, late JPEG loads, and reconnection without leaving old callbacks', async () => {
  const f = fixture({ storedCanvas: 'true' }), ws = await f.connect();
  ws.onmessage({ data: {} }); const lateImage = f.pending().onload;
  await f.offer(ws);
  assert.equal(f.element('video-canvas').hidden, false);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video').classList.contains('offscreen-video'), true);
  lateImage(); assert.equal(f.element('screen').hidden, true);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.deepEqual(ws.sent.filter(m => m.type === 'touch').map(m => m.contacts[0].down), [true, false]);
  ws.onmessage({ data: JSON.stringify({ type: 'status', width: 1280, height: 720, streaming: true, rtcAvailable: true }) });
  assert.match(f.element('status').textContent, /WebRTC \/ Canvas/);
  const lateFrames = [...f.videoCallbacks.values()], lateRefresh = [...f.raf.values()];
  ws.close(); assert.equal(f.element('video-canvas').hidden, true);
  assert.equal(f.videoCallbacks.size, 0); assert.equal(f.raf.size, 0);
  const replacement = await f.connect(); await f.offer(replacement, 2); const count = f.draws.length;
  lateFrames.forEach(fn => fn(100, { presentedFrames: 100, mediaTime: .1 })); lateRefresh.forEach(fn => fn());
  assert.equal(f.draws.length, count); assert.equal(f.element('video-canvas').hidden, false);
});

test('Canvas failure falls back to JPEG while control and the selected preference remain available', async () => {
  const f = fixture({ storedCanvas: 'true', canvasAvailable: false }), ws = await f.connect(); await f.offer(ws);
  assert.equal(ws.readyState, 1);
  assert.equal(f.peers[0].closed, true);
  assert.equal(f.element('video-canvas').hidden, true);
  assert.equal(f.element('video').hidden, true);
  assert.equal(f.element('video').classList.contains('offscreen-video'), false);
  assert.equal(f.timers.size > 0 && [...f.timers.values()].some(t => t.delay === 1000), false);
  assert.equal(ws.sent.at(-1).type, 'rtc-fallback');
  f.frame(ws);
  // The swap decides which buffer is on screen, so assert on the surface rather than one element.
  assert.equal([f.element('screen'), f.element('screen-next')].filter(buffer => !buffer.hidden).length, 1);
  f.pointer('pointerdown', 100, 'touch-surface'); f.pointer('pointerup', 100, 'touch-surface');
  assert.equal(ws.sent.filter(m => m.type === 'touch').length, 2);
  assert.equal(f.element('canvas-video').checked, true);
});


test('phone connection requires pairing and reports the server result', async () => {
  const f = fixture();
  f.element('phone-connect').handlers.click();
  assert.equal(f.sockets.length, 0);
  assert.match(f.element('phone-connect-message').textContent, /重新配对/);
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


test('interrupted display resumes with fresh authentication after a server restart', async () => {
  const f = fixture({ resume: () => ({ token: 'fresh-session', name: 'Chrome' }) });
  const old = await f.connect(); old.close();
  const retry = [...f.timers.values()].find(t => t.delay === 1000);
  assert.ok(retry); await retry.fn();
  assert.equal(f.sockets.length, 2);
  assert.equal(f.sockets[1].url, 'ws://server:8080/stream?token=fresh-session');
});

test('revocation during an active display stops retries and requires fresh pairing', async () => {
  const f = fixture({ resume: () => { const error = new Error('revoked'); error.status = 401; throw error; } });
  const old = await f.connect(); old.close();
  await [...f.timers.values()].find(t => t.delay === 1000).fn();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.sockets.length, 1);
  assert.equal(f.element('code-entry').hidden, false);
  assert.equal(f.preferences.has('wheelplay.browserDevice'), false);
});

test('createImageBitmap renders JPEG to Canvas without ObjectURLs, pipelines 1-frame ACK, and closes bitmaps', async () => {
  const decoders = [], closedBitmaps = [];
  const f = fixture({
    createImageBitmap: payload => new Promise(resolve => {
      decoders.push({
        payload,
        resolve: (width = 1280, height = 720) => {
          const bitmap = { width, height, close() { closedBitmaps.push(payload); } };
          resolve(bitmap);
        },
      });
    }),
  });
  const ws = await f.connect();
  // Frame 1 starts decoding immediately and sends ACK right away so network transfer overlaps decode.
  ws.onmessage({ data: { frame: 1 } });
  assert.equal(decoders.length, 1);
  assert.equal(ws.sent.filter(m => m.type === 'ack').length, 1);
  assert.equal(f.objectUrls.length, 0);

  // Frame 2 and Frame 3 arrive while Frame 1 is still decoding: only the latest is kept in the
  // single pending slot, and no extra ACK is sent until Frame 1 finishes decoding.
  ws.onmessage({ data: { frame: 2 } });
  ws.onmessage({ data: { frame: 3 } });
  assert.equal(decoders.length, 1);
  assert.equal(ws.sent.filter(m => m.type === 'ack').length, 1);

  decoders[0].resolve(1600, 900);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.draws.length, 1);
  assert.equal(f.element('video-canvas').hidden, false);
  assert.equal(f.element('video-canvas').width, 1600);
  assert.equal(f.element('video-canvas').height, 900);
  assert.equal(f.element('screen').hidden, true);
  assert.deepEqual(closedBitmaps, [{ frame: 1 }]);

  // Completing Frame 1 immediately starts Frame 3 and sends the next pipelined ACK.
  assert.equal(decoders.length, 2);
  assert.deepEqual(decoders[1].payload, { frame: 3 });
  assert.equal(ws.sent.filter(m => m.type === 'ack').length, 2);

  // Frame 4 arrives while Frame 3 is decoding, then both Frame 3 and Frame 4 resolve within the
  // same VSync window: Frame 3 is superseded and closed without a redundant drawImage, and only
  // Frame 4 is painted on the next VSync tick.
  ws.onmessage({ data: { frame: 4 } });
  decoders[1].resolve(1600, 900);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.draws.length, 1, 'Second decode in the same VSync must wait for rAF');
  assert.equal(decoders.length, 3);
  decoders[2].resolve(1600, 900);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.draws.length, 1);
  assert.deepEqual(closedBitmaps, [{ frame: 1 }, { frame: 3 }]);
  f.flushMoves();
  assert.equal(f.draws.length, 2);
  assert.deepEqual(closedBitmaps, [{ frame: 1 }, { frame: 3 }, { frame: 4 }]);
  assert.equal(f.objectUrls.length, 0);
});

test('in-flight createImageBitmap closes its bitmap and does not draw after WebRTC handover or disconnect', async () => {
  const decoders = [], closed = [];
  const f = fixture({
    createImageBitmap: payload => new Promise(resolve => {
      decoders.push(() => resolve({ width: 1280, height: 720, close() { closed.push(payload.id); } }));
    }),
  });
  const ws = await f.connect();
  ws.onmessage({ data: { id: 'before-rtc' } });
  await f.offer(ws);
  assert.equal(f.element('video').hidden, false);
  assert.equal(f.element('video-canvas').hidden, true);
  decoders[0]();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(closed, ['before-rtc']);
  assert.equal(f.draws.length, 0);
  assert.equal(f.element('video-canvas').hidden, true);

  ws.close();
  const next = await f.connect();
  next.onmessage({ data: { id: 'before-close' } });
  next.close();
  decoders[1]();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(closed, ['before-rtc', 'before-close']);
  assert.equal(f.draws.length, 0);
});

test('touch drags reuse cached bounding rect and invalidate on resize or fullscreen change', async () => {
  const f = fixture(), ws = await f.connect(); f.frame(ws);
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

