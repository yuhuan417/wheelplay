// Coordinates are normalized in the visible video rectangle, excluding letterboxing.
export function mapPoint(clientX, clientY, rect, width, height, clamp = false) {
  if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const w = width * scale, h = height * scale;
  const x = (clientX - rect.left - (rect.width - w) / 2) / w;
  const y = (clientY - rect.top - (rect.height - h) / 2) / h;
  if (!clamp && (x < 0 || x > 1 || y < 0 || y > 1)) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

// Keep essential controls until WebSocket accepts them. A successful send is never retried.
export function createControlOutbox(socket, {
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(), onFailure = () => socket.close(),
} = {}) {
  const touches = [];
  const signals = [];
  let audioAck = null, audioReady = null, ping = null, viewport = null, timer = null, blockedSince = null, closed = false;
  const close = () => {
    closed = true; cancel(timer); timer = null;
    audioAck = null; audioReady = null; ping = null; viewport = null; touches.length = 0; signals.length = 0;
  };
  const fail = () => { close(); onFailure(); };
  const flush = () => {
    if (closed) return;
    if (socket.readyState !== 1) { close(); return; }
    try {
      while ((audioAck || audioReady || touches.length || signals.length || viewport || ping) && socket.bufferedAmount < 8192) {
        if (audioAck) { socket.send(audioAck); audioAck = null; }
        else if (audioReady) { socket.send(audioReady); audioReady = null; }
        else if (touches.length) { socket.send(touches[0].json); touches.shift(); }
        else if (signals.length) { socket.send(signals[0]); signals.shift(); }
        else if (viewport) { socket.send(viewport); viewport = null; }
        else { socket.send(ping); ping = null; }
        blockedSince = null;
      }
    } catch (_) { fail(); return; }
    if (audioAck || audioReady || touches.length || signals.length || viewport || ping) {
      if (blockedSince === null) blockedSince = now();
      if (now() - blockedSince >= 1500) { fail(); return; }
      if (timer === null) timer = schedule(() => { timer = null; flush(); }, 10);
    } else {
      cancel(timer); timer = null; blockedSince = null;
    }
  };
  const PING_JSON = '{"type":"ping"}';
  return {
    send(data, kind = 'edge') {
      if (closed || socket.readyState !== 1) return false;
      if (data.type === 'ping') ping = PING_JSON;
      else {
        const json = JSON.stringify(data);
        if (data.type === 'audio-ack') audioAck = json;
        else if (data.type === 'audio-ready') audioReady = json;
        else if (data.type === 'viewport') viewport = json;
        else if (data.type === 'touch') {
          const tail = touches[touches.length - 1];
          // Only consecutive moves may be replaced; never cross a down/up/release boundary.
          if (kind === 'move' && tail && tail.kind === 'move') tail.json = json;
          else if (touches.length < 64) touches.push({ kind, json });
          else { fail(); return false; }
        } else if (['webcodecs-start', 'webcodecs-ready', 'webcodecs-keyframe',
          'webcodecs-fallback', 'webcodecs-unsupported', 'phone-connect'].includes(data.type)) {
          if (signals.length >= 8 || json.length > 32768) { fail(); return false; }
          signals.push(json);
        } else return false;
      }
      flush();
      return !closed;
    },
    close,
  };
}

// The phone forwards CarPlay's H.264/H.265 NAL units over the control WebSocket and the
// browser decodes them with WebCodecs. The payload shares the socket with audio and control
// messages. WebCodecs is only exposed in a secure context, so video requires HTTPS.
export const WEBCODECS_VIDEO_MAGIC = 0x31435657; // "WVC1"

/** Converts Annex B NAL units to the 4-byte length framing WebCodecs uses with avcC/hvcC. */
export function annexBToAvcc(data) {
  const units = [];
  let offset = 0;
  while (offset + 3 <= data.length) {
    let start = -1;
    if (data[offset] === 0 && data[offset + 1] === 0 && data[offset + 2] === 1) start = offset + 3;
    else if (offset + 4 <= data.length && data[offset] === 0 && data[offset + 1] === 0 &&
      data[offset + 2] === 0 && data[offset + 3] === 1) start = offset + 4;
    if (start < 0) { offset += 1; continue; }
    let next = data.length;
    for (let scan = start; scan + 2 < data.length; scan += 1) {
      if (data[scan] === 0 && data[scan + 1] === 0 && data[scan + 2] === 1) { next = scan; break; }
      if (scan + 3 < data.length && data[scan] === 0 && data[scan + 1] === 0 &&
        data[scan + 2] === 0 && data[scan + 3] === 1) { next = scan; break; }
    }
    if (next > start) units.push([start, next]);
    offset = next;
  }
  if (units.length === 0) return data;
  let total = 0;
  for (const [start, end] of units) total += 4 + (end - start);
  const output = new Uint8Array(total);
  const view = new DataView(output.buffer);
  let out = 0;
  for (const [start, end] of units) {
    view.setUint32(out, end - start);
    out += 4;
    output.set(data.subarray(start, end), out);
    out += end - start;
  }
  return output;
}

function reverseBits32(value) {
  let result = 0;
  for (let index = 0; index < 32; index += 1) {
    result = ((result << 1) | (value & 1)) >>> 0;
    value >>>= 1;
  }
  return result >>> 0;
}

function hevcCodecCandidates(codecData, width, height) {
  const candidates = [];
  const base = (codec, description) => ({
    config: {
      codec,
      description,
      hardwareAcceleration: 'no-preference',
      optimizeForLatency: true,
      ...(width > 0 && height > 0 ? { codedWidth: width, codedHeight: height } : {}),
    },
    description: true,
  });
  if (codecData.length >= 13) {
    const view = new DataView(codecData.buffer, codecData.byteOffset, codecData.byteLength);
    const profileSpace = ['', 'A', 'B', 'C'][(codecData[1] >> 6) & 0x03] || '';
    const tier = (codecData[1] & 0x20) !== 0 ? 'H' : 'L';
    const profileIdc = codecData[1] & 0x1f;
    const compatibility = reverseBits32(view.getUint32(2)) >>> 0;
    const levelIdc = codecData[12];
    const constraints = [];
    for (let index = 6; index <= 11; index += 1) constraints.push(codecData[index]);
    while (constraints.length > 0 && constraints[constraints.length - 1] === 0) constraints.pop();
    const constraintHex = constraints.map(value => value.toString(16).padStart(2, '0')).join('').toUpperCase();
    const profile = `hvc1.${profileSpace}${profileIdc}.${compatibility.toString(16)}.${tier}${levelIdc}`;
    if (constraintHex) candidates.push(base(`${profile}.${constraintHex}`, codecData.slice()));
    candidates.push(base(profile, codecData.slice()));
  }
  // Last resort for firmware that hides the exact record; the browser rejects it if unsupported.
  candidates.push(base('hvc1.1.6.L120.B0', codecData.slice()));
  return candidates;
}

function h264CodecCandidate(codecData, width, height) {
  const hasDescription = codecData.length > 4;
  const hex = value => value.toString(16).padStart(2, '0');
  return {
    config: {
      codec: hasDescription ? `avc1.${hex(codecData[1])}${hex(codecData[2])}${hex(codecData[3])}` : 'avc1.42E01E',
      hardwareAcceleration: 'no-preference',
      optimizeForLatency: true,
      ...(hasDescription ? { description: codecData.slice() } : {}),
      ...(width > 0 && height > 0 ? { codedWidth: width, codedHeight: height } : {}),
    },
    description: hasDescription,
  };
}

export function createWebCodecsReceiver(canvas, {
  send = () => {}, onReady = () => {}, onError = () => {}, onFrame = () => {},
  createDecoder = init => new VideoDecoder(init),
  schedule = setTimeout, cancel = clearTimeout, now = () => performance.now(),
} = {}) {
  let decoder = null, configured = false, usesDescription = false;
  let generation = 0, ready = false, needKeyframe = true, failed = false;
  let codec = 'H264', width = 0, height = 0, context = null;
  let lastTimestamp = 0, lastPaintAt = 0, watchdog = null;
  let lastCodecString = '', lastDescriptionBytes = 0, lastPtsUs = 0, lastFrameBytes = 0;
  const probes = [];
  const stats = { configs: 0, frames: 0, keyframes: 0, deltas: 0, dropped: 0, decoded: 0, errors: 0 };

  function closeDecoder() {
    const old = decoder;
    decoder = null; configured = false;
    if (old) { try { old.close(); } catch (_) {} }
  }
  function stopWatchdog() {
    if (watchdog !== null) { cancel(watchdog); watchdog = null; }
  }
  function armWatchdog() {
    stopWatchdog();
    watchdog = schedule(() => {
      watchdog = null;
      if (!configured || failed) return;
      const elapsed = now() - lastPaintAt;
      if (elapsed > 3000) send({ type: 'webcodecs-keyframe' });
      if (ready && elapsed > 10000) { fail('no decoded frame'); return; }
      armWatchdog();
    }, 2000);
  }
  // Everything the server needs to tell why the browser could not show the stream. This is
  // written to the persistent diagnostic log, not only logcat.
  function diagnostics(reason, error) {
    return {
      reason: String(reason || '').slice(0, 200),
      codec, width, height,
      codecString: lastCodecString,
      descriptionBytes: lastDescriptionBytes,
      configured,
      decoderState: decoder ? decoder.state : null,
      decodeQueueSize: decoder && Number.isFinite(decoder.decodeQueueSize) ? decoder.decodeQueueSize : null,
      lastPtsUs,
      lastFrameBytes,
      canvas: { context: Boolean(context), width: canvas.width, height: canvas.height },
      probes: probes.slice(0, 8),
      stats: { ...stats },
      lastPaintAgoMs: lastPaintAt ? Math.max(0, Math.round(now() - lastPaintAt)) : null,
      secureContext: typeof window !== 'undefined' ? window.isSecureContext : null,
      userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
      error: error ? {
        name: String(error.name || ''),
        message: String(error.message || error).slice(0, 300),
      } : null,
    };
  }
  function fail(reason, error = null) {
    if (failed) return;
    failed = true;
    ready = false;
    const text = String(reason && reason.message ? reason.message : reason || 'decode failed').slice(0, 200);
    const cause = error || (reason instanceof Error ? reason : null);
    closeDecoder(); stopWatchdog();
    stats.errors += 1;
    onError(text);
    send({ type: 'webcodecs-fallback', reason: text, detail: diagnostics(text, cause) });
  }
  function nextTimestamp(ptsUs) {
    const wanted = Number.isFinite(ptsUs) ? Math.round(ptsUs) : lastTimestamp + 16667;
    lastTimestamp = Math.max(lastTimestamp + 1, wanted);
    return lastTimestamp;
  }
  function paint(frame, token) {
    try {
      if (token !== generation) return;
      const frameWidth = frame.displayWidth || width || 0;
      const frameHeight = frame.displayHeight || height || 0;
      if (!frameWidth || !frameHeight) return;
      if (!context) {
        context = canvas.getContext('2d', { alpha: false }) || canvas.getContext('2d');
        if (!context) throw new Error('Canvas 2D unavailable');
      }
      if (canvas.width !== frameWidth || canvas.height !== frameHeight) {
        canvas.width = frameWidth; canvas.height = frameHeight;
      }
      context.drawImage(frame, 0, 0, frameWidth, frameHeight);
      width = frameWidth; height = frameHeight; lastPaintAt = now();
      stats.decoded += 1;
      if (!ready) { ready = true; onReady(); }
      onFrame(frameWidth, frameHeight);
    } catch (error) {
      fail(error);
    } finally {
      try { frame.close(); } catch (_) {}
    }
  }
  async function configure(message) {
    const token = ++generation;
    closeDecoder(); stopWatchdog();
    failed = false; ready = false; needKeyframe = true;
    codec = message.codec; width = message.width || 0; height = message.height || 0;
    usesDescription = false;
    stats.configs += 1; probes.length = 0; lastCodecString = ''; lastDescriptionBytes = 0;
    const candidates = message.codec === 'H265'
      ? hevcCodecCandidates(message.codecData, width, height)
      : [h264CodecCandidate(message.codecData, width, height)];
    // H.264 is the baseline every WebCodecs browser accepts; probing it would only delay the
    // first keyframe. HEVC needs the probe because support varies by platform.
    const probe = message.codec === 'H265' &&
      typeof VideoDecoder !== 'undefined' && typeof VideoDecoder.isConfigSupported === 'function';
    for (const candidate of candidates) {
      if (token !== generation) return;
      if (probe) {
        const support = await VideoDecoder.isConfigSupported(candidate.config).catch(error => {
          probes.push({ codec: candidate.config.codec, supported: false, error: String(error && error.message || error).slice(0, 120) });
          return null;
        });
        if (token !== generation) return;
        probes.push({ codec: candidate.config.codec, supported: support ? support.supported !== false : false });
        if (!support || support.supported === false) continue;
      } else {
        probes.push({ codec: candidate.config.codec, supported: 'unprobed' });
      }
      let next = null;
      try {
        next = createDecoder({
          output: frame => paint(frame, token),
          error: error => { if (token === generation) fail(error); },
        });
        next.configure(candidate.config);
        decoder = next; configured = true; usesDescription = candidate.description;
        lastCodecString = candidate.config.codec;
        lastDescriptionBytes = candidate.description && candidate.config.description
          ? candidate.config.description.length : 0;
        lastPaintAt = now();
        armWatchdog();
        // The keyframe may have arrived while an asynchronous codec probe was running.
        send({ type: 'webcodecs-keyframe' });
        return;
      } catch (error) {
        probes.push({ codec: candidate.config.codec, supported: 'configure failed',
          error: String(error && error.message || error).slice(0, 120) });
        try { next?.close?.(); } catch (_) {}
      }
    }
    fail(message.codec === 'H265' ? 'H.265 decoder unavailable' : 'H.264 decoder unavailable');
  }
  function push(message) {
    if (failed || !configured || !decoder) return;
    stats.frames += 1;
    if (message.keyframe) stats.keyframes += 1; else stats.deltas += 1;
    if (needKeyframe && !message.keyframe) { stats.dropped += 1; return; }
    if (message.keyframe) needKeyframe = false;
    const data = usesDescription ? annexBToAvcc(message.data) : message.data;
    if (!data || data.length === 0) return;
    lastPtsUs = message.ptsUs;
    lastFrameBytes = data.length;
    if ((decoder.decodeQueueSize || 0) > 16) {
      try { decoder.reset(); } catch (_) {}
      needKeyframe = true;
      stats.dropped += 1;
      send({ type: 'webcodecs-keyframe' });
      return;
    }
    try {
      decoder.decode(new EncodedVideoChunk({
        type: message.keyframe ? 'key' : 'delta',
        timestamp: nextTimestamp(message.ptsUs),
        data,
      }));
    } catch (error) { fail(error); }
  }
  function receive(buffer) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 16) return false;
    const view = new DataView(buffer);
    if (view.getUint32(0, true) !== WEBCODECS_VIDEO_MAGIC) return false;
    const type = view.getUint8(4);
    if (type === 1) {
      if (buffer.byteLength < 21) return true;
      void configure({
        codec: view.getUint8(16) === 2 ? 'H265' : 'H264',
        width: view.getUint32(8, true),
        height: view.getUint32(12, true),
        codecData: new Uint8Array(buffer, 20).slice(),
      });
    } else if (type === 2) {
      push({
        keyframe: (view.getUint8(6) & 1) === 1,
        ptsUs: Number(view.getBigUint64(8, true)),
        data: new Uint8Array(buffer, 16),
      });
    }
    return true;
  }
  function close() {
    generation += 1;
    closeDecoder(); stopWatchdog();
    ready = false; needKeyframe = true; failed = false; lastTimestamp = 0;
  }
  return { receive, close, get active() { return ready; }, get codec() { return codec; },
    get width() { return width; }, get height() { return height; } };
}

import { createBrowserAudio } from './browser-audio.js';

if (typeof document !== 'undefined') {
  const $ = id => document.getElementById(id);
  const wcCanvas = $('wc-canvas');
  const display = $('display');
  const touchSurface = $('touch-surface');
  // Read-only diagnostic snapshots; counters never include video payloads.
  const performanceStats = { source: null, transport: null };
  Object.defineProperty(window, 'wheelplayStats', { configurable: true, get: () => JSON.parse(JSON.stringify(performanceStats)) });
  let webCodecs = null, webCodecsRequested = false, webCodecsHttpsNotified = false, webCodecsUnsupportedSent = false;
  let webCodecsError = null, webCodecsStopTimer = null;
  function videoTransportActive() {
    return Boolean(webCodecs && webCodecs.active);
  }
  let socket = null, stopped = true, retry = null, attempts = 0;
  let width = 1280, height = 720, live = false, lastFrame = 0, lastStatus = 0;
  let heartbeat = null, moveRaf = null, moveDirty = false, moveGeneration = 0, outbox = null;
  let launchPresentation = null;
  let viewportReportTimer = null, lastViewportSignature = '';
  const hideToolbarOption = $('hide-toolbar');
  const hideToolbarPreference = 'wheelplay.hideToolbar';
  try { hideToolbarOption.checked = window.localStorage.getItem(hideToolbarPreference) === 'true'; } catch (_) {}
  hideToolbarOption.addEventListener('change', () => {
    try { window.localStorage.setItem(hideToolbarPreference, String(hideToolbarOption.checked)); } catch (_) {}
  });
  const pointers = new Map();
  const send = (data, kind) => outbox ? outbox.send(data, kind) : false;
  const browserAudio = createBrowserAudio({
    send,
    sendBinary: packet => {
      if (socket?.readyState !== WebSocket.OPEN || socket.bufferedAmount > 8192) return false;
      try { socket.send(packet); return true; } catch (_) { return false; }
    },
    onState: ({ error }) => {
      $('audio-message').textContent = error;
      $('audio-message').hidden = !error || !paired;
    },
  });
  document.addEventListener('pointerdown', () => browserAudio.unlock(), { capture: true });
  $('secure-setup').addEventListener('toggle', async () => {
    if (!$('secure-setup').open) return;
    $('https-entry').removeAttribute('href');
    try {
      const response = await fetch('/tls.json', { cache: 'no-store' });
      const info = await response.json();
      if (info.available && Number.isInteger(info.port) && info.port >= 1 && info.port <= 65535) {
        const endpoint = new URL(location.href);
        endpoint.protocol = 'https:'; endpoint.port = String(info.port); endpoint.pathname = '/';
        endpoint.search = ''; endpoint.hash = '';
        $('https-entry').href = endpoint.href;
      }
      $('tls-fingerprint').textContent = info.enabled === false ? 'HTTPS 已关闭，请在 App 的 Web 监听中开启。'
        : info.fingerprint || 'HTTPS 正在准备，请稍后重新展开。';
      $('tls-error').textContent = info.error || '';
    } catch (_) { $('tls-error').textContent = '证书信息暂时无法读取'; }
  });
  let cachedDisplayRect = null, cachedRectSurface = null;
  function invalidateDisplayRect() { cachedDisplayRect = null; cachedRectSurface = null; }
  function getSurfaceRect(surface) {
    if (cachedRectSurface !== surface || !cachedDisplayRect || cachedDisplayRect.width <= 0 || cachedDisplayRect.height <= 0) {
      cachedDisplayRect = surface.getBoundingClientRect();
      cachedRectSurface = surface;
    }
    return cachedDisplayRect;
  }
  function scheduleViewportReport() {
    invalidateDisplayRect();
    clearTimeout(viewportReportTimer);
    viewportReportTimer = setTimeout(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN || display.hidden) return;
      // Measure the browser viewport, not #display: hiding/showing the top bar changes the
      // display box and must not look like a new CarPlay resolution request. Otherwise the
      // resulting reconnect flips the waiting/live layout and can loop every second.
      const root = document.documentElement;
      const viewWidth = window.innerWidth || root.clientWidth;
      const viewHeight = window.innerHeight || root.clientHeight;
      const rawScale = window.devicePixelRatio || 1;
      const maxEdge = Math.max(viewWidth, viewHeight) * rawScale;
      const scale = maxEdge > 1280 ? rawScale * (1280 / maxEdge) : rawScale;
      const width = Math.round(viewWidth * scale / 2) * 2;
      const height = Math.round(viewHeight * scale / 2) * 2;
      if (width < 320 || height < 240) return;
      const signature = `${width}x${height}`;
      if (signature === lastViewportSignature) return;
      lastViewportSignature = signature;
      send({ type: 'viewport', width, height });
    }, 180);
  }
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(scheduleViewportReport).observe(display);
  window.addEventListener('resize', scheduleViewportReport);
  function cancelMove() {
    moveGeneration++;
    if (moveRaf !== null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(moveRaf);
    moveRaf = null;
    moveDirty = false;
  }
  function scheduleMoveGate() {
    if (moveRaf !== null || typeof requestAnimationFrame !== 'function') return;
    const generation = moveGeneration;
    moveRaf = requestAnimationFrame(() => {
      moveRaf = null;
      if (generation !== moveGeneration) return;
      if (!moveDirty || !pointers.size) {
        moveDirty = false;
        return;
      }
      moveDirty = false;
      send({ type: 'touch', contacts: contacts() }, 'move');
      scheduleMoveGate();
    });
  }
  function contacts() { return [...pointers.values()].map(p => ({ id: p.id, x: p.x, y: p.y, down: true })); }
  function release() { cancelMove(); pointers.clear(); send({ type: 'touch', contacts: [] }); }
  const statusElement = $('status');
  const waitingElement = $('waiting');
  // Called on every transport state change, so it must not touch the DOM while unchanged.
  // The WebCodecs canvas and the touch overlay both carry the stale class.
  function setLive(value) {
    if (live === value) return;
    live = value;
    wcCanvas.classList.toggle('stale', !value);
    touchSurface.classList.toggle('stale', !value);
    waitingElement.hidden = value;
    if (!display.hidden) {
      $('topbar').hidden = Boolean(launchPresentation?.immersive) || (value && hideToolbarOption.checked);
    }
    if (!value && pointers.size) release();
  }
  // Routine callers repeat the same text every frame or every status message; a held message
  // outranks them until it expires so a one-shot event stays readable.
  let statusHeldUntil = 0, lastStatusText = null;
  function setStatus(text, holdMillis = 0) {
    if (holdMillis === 0 && statusHeldUntil === 0 && text === lastStatusText && statusElement.textContent === text) return;
    const now = Date.now();
    if (now < statusHeldUntil && holdMillis === 0) return;
    // Comparing against the live DOM keeps this correct even when another path wrote directly.
    if (statusElement.textContent !== text) statusElement.textContent = text;
    lastStatusText = text;
    statusHeldUntil = holdMillis > 0 ? now + holdMillis : 0;
  }
  function hideWebCodecsSurface() {
    wcCanvas.hidden = true;
  }
  // A reconfiguration briefly stops the decoder. Keep the last decoded frame on screen while
  // the replacement config/keyframe arrives; only hide the surface if it really stays stopped.
  function scheduleWebCodecsHide() {
    clearTimeout(webCodecsStopTimer);
    webCodecsStopTimer = setTimeout(() => {
      webCodecsStopTimer = null;
      if (!webCodecs || !webCodecs.active) {
        hideWebCodecsSurface();
        setLive(false);
      }
    }, 3000);
  }
  // There is no fallback transport: keep the failure visible so the cause can be fixed.
  function webCodecsFailed(reason) {
    if (webCodecs) webCodecs.close();
    webCodecsRequested = false;
    webCodecsError = reason;
    wcCanvas.hidden = true;
    setLive(false);
    $('stage').textContent = `WebCodecs 失败：${reason}`;
    $('waiting').hidden = false;
    setStatus(`WebCodecs 失败 · 已停止：${reason}`, 60000);
  }
  function cleanupVideoTransports() {
    clearTimeout(webCodecsStopTimer); webCodecsStopTimer = null;
    if (webCodecs) webCodecs.close(); webCodecs = null;
    webCodecsRequested = false; webCodecsHttpsNotified = false; webCodecsUnsupportedSent = false;
    webCodecsError = null;
    wcCanvas.hidden = true;
    performanceStats.source = performanceStats.transport = null;
  }
  function exitLaunchFullscreen() {
    try { Promise.resolve(document.exitFullscreen()).catch(() => {}); } catch (_) {}
  }
  function prepareLaunchPresentation() {
    const presentation = { immersive: $('fullscreen-on-start').checked, ownsFullscreen: false, cancelled: false };
    launchPresentation = presentation;
    if (!presentation.immersive || document.fullscreenElement || !document.documentElement?.requestFullscreen) return;
    presentation.ownsFullscreen = true;
    try {
      // Keep the request in the submit gesture, before WebSocket setup.
      Promise.resolve(document.documentElement.requestFullscreen()).then(() => {
        if (presentation.cancelled && !launchPresentation?.immersive && document.fullscreenElement) exitLaunchFullscreen();
      }).catch(() => { presentation.ownsFullscreen = false; });
    } catch (_) { presentation.ownsFullscreen = false; }
  }
  function restoreLaunchPresentation() {
    if (!launchPresentation) return;
    launchPresentation.cancelled = true;
    launchPresentation.immersive = false;
    if (launchPresentation.ownsFullscreen && document.fullscreenElement) exitLaunchFullscreen();
  }
  function disconnect() {
    restoreLaunchPresentation();
    stopped = true; release();
    if (outbox) { outbox.close(); outbox = null; }
    clearTimeout(retry); clearInterval(heartbeat);
    if (socket) { const old = socket; socket = null; old.close(); }
    cleanupVideoTransports(); setLive(false);
    browserAudio.close(); $('audio-message').hidden = true;
    $('topbar').hidden = false; $('pairing').hidden = false; $('display').hidden = true;
    invalidateDisplayRect();
    $('disconnect').hidden = true; $('connect').disabled = false;
    $('status').textContent = '已断开';
  }
  function connect() {
    clearTimeout(retry);
    $('connect').disabled = true;
    $('status').textContent = '正在连接…';
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream`);
    ws.binaryType = 'arraybuffer';
    socket = ws;
    const connectTimer = setTimeout(() => ws.close(), 8000);
    ws.onopen = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) outbox.close();
      outbox = createControlOutbox(ws, { onFailure: () => {
        if (socket !== ws) return;
        cancelMove(); pointers.clear(); setLive(false); ws.close();
      } });
      attempts = 0; lastStatus = Date.now();
      browserAudio.attach();
      cleanupVideoTransports();
      webCodecs = createWebCodecsReceiver(wcCanvas, { send,
        onReady: () => {
          if (socket !== ws) return;
          clearTimeout(webCodecsStopTimer); webCodecsStopTimer = null;
          webCodecsError = null;
          wcCanvas.hidden = false;
          setLive(true);
          send({ type: 'webcodecs-ready' });
        },
        onFrame: (frameWidth, frameHeight) => {
          if (socket !== ws) return;
          width = frameWidth; height = frameHeight; lastFrame = Date.now();
          wcCanvas.hidden = false;
          setLive(true);
        },
        onError: reason => {
          if (socket !== ws) return;
          webCodecsFailed(reason);
        },
      });
      $('message').textContent = '';
      $('phone-connect').disabled = false;
      $('phone-connect-message').textContent = '请保持 Android 上的 WheelPlay 在前台；点击连接 iPhone，按提示完成授权。';
      $('pairing').hidden = true; $('display').hidden = false; $('disconnect').hidden = false;
      $('topbar').hidden = hideToolbarOption.checked || Boolean(launchPresentation?.immersive);
      lastViewportSignature = '';
      scheduleViewportReport();
      $('status').textContent = '已连接 · 等待画面';
      send({ type: 'ping' });
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        if (Date.now() - lastStatus > 6000) { ws.close(); return; }
        send({ type: 'ping' });
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
      }, 400);
    };
    ws.onmessage = event => {
      if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
      if (typeof event.data === 'string') {
        const data = JSON.parse(event.data);
        if (browserAudio.control(data)) return;
        if (data.type === 'webcodecs-stop') {
          webCodecsRequested = false;
          if (webCodecs) webCodecs.close();
          // Keep the last frame visible; the server normally sends a new config and keyframe
          // immediately after a source/codec reconfiguration.
          scheduleWebCodecsHide();
          return;
        }
        if (data.type === 'webcodecs-error') {
          webCodecsFailed(data.reason || '解码失败');
          return;
        }
        if (data.type === 'phone-connect-result') {
          $('phone-connect').disabled = false;
          $('phone-connect-message').textContent = data.message;
          return;
        }
        if (data.type === 'host-ui') {
          disconnect(); return;
        }
        if (data.type === 'busy') {
          const message = data.message; disconnect(); $('message').textContent = message; return;
        }
        if (data.type === 'status') {
          performanceStats.source = data.performance?.source ?? null;
          performanceStats.transport = data.transport ?? null;
          lastStatus = Date.now(); width = data.width; height = data.height;
          $('stage').textContent = data.stage || '等待 iPhone 画面…';
          if (data.webCodecsNeedsHttps && !webCodecsHttpsNotified) {
            webCodecsHttpsNotified = true;
            webCodecsFailed('需要 HTTPS 页面（WebCodecs 不在 HTTP 下提供）');
          }
          if (data.webCodecsAvailable && webCodecsError !== null) webCodecsError = null;
          if (data.webCodecsError && data.webCodecsError !== webCodecsError) {
            webCodecsFailed(data.webCodecsError);
          }
          if (data.webCodecsAvailable && typeof VideoDecoder !== 'undefined' && !webCodecsRequested &&
            window.isSecureContext) {
            webCodecsRequested = true; send({ type: 'webcodecs-start' });
          }
          if (data.webCodecsAvailable && typeof VideoDecoder === 'undefined' && !webCodecsUnsupportedSent) {
            webCodecsUnsupportedSent = true;
            send({ type: 'webcodecs-unsupported', reason: 'VideoDecoder requires HTTPS',
              detail: {
                userAgent: navigator.userAgent,
                secureContext: window.isSecureContext,
                videoDecoder: typeof VideoDecoder,
                protocol: location.protocol,
              } });
          }
          // The server resets availability when the iPhone starts a new video configuration.
          if (!data.webCodecsAvailable) webCodecsRequested = false;
          const recentWebCodecsFrame = webCodecs !== null && lastFrame > 0 &&
            Date.now() - lastFrame < 3000 && webCodecsError === null;
          setLive(Boolean(data.streaming || recentWebCodecsFrame));
          if (!live && !data.streaming) {
            $('topbar').hidden = Boolean(launchPresentation?.immersive);
          }
          const transport = webCodecsError
            ? 'WebCodecs 失败'
            : webCodecs?.active
              ? `${webCodecs.codec === 'H265' ? 'HEVC' : 'H.264'} / WebCodecs`
              : 'WebCodecs';
          setStatus(live ? `${transport} · 触控已连接` : '已连接 · 等待 iPhone 画面');
        }
        if (data.type === 'input-unavailable') setStatus('等待 iPhone 触控通道就绪');
        return;
      }
      if (browserAudio.receive(event.data)) return;
      if (webCodecs && webCodecs.receive(event.data)) return;
      // WebCodecs is the only binary video transport; unmatched payloads are ignored.
    };
    ws.onclose = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) { outbox.close(); outbox = null; }
      cancelMove();
      socket = null; pointers.clear(); clearInterval(heartbeat); cleanupVideoTransports(); setLive(false);
      browserAudio.close(); $('audio-message').hidden = true;
      if (stopped) return;
      if (++attempts > 8) {
        disconnect(); $('status').textContent = '连接未成功';
        $('message').textContent = '请检查服务端是否运行，以及车机与手机是否在同一局域网。';
        return;
      }
      $('status').textContent = '连接中断 · 正在重试';
      $('stage').textContent = '检查服务端与车机是否仍在同一局域网。';
      retry = setTimeout(() => { if (!stopped) connect(); },
        Math.min(8000, 500 * 2 ** Math.min(attempts, 5)));
    };
    ws.onerror = () => {}; // onclose owns retry and visible error state.
  }
  $('connect-form').addEventListener('submit', event => {
    event.preventDefault();
    prepareLaunchPresentation();
    $('connect').disabled = true; $('message').textContent = '';
    stopped = false; attempts = 0; connect();
  });
  $('phone-connect').addEventListener('click', () => {
    if (!socket || socket.readyState !== WebSocket.OPEN || !send({ type: 'phone-connect' })) {
      $('phone-connect-message').textContent = '浏览器连接已中断，请重新连接后再试。';
      return;
    }
    $('phone-connect').disabled = true;
    $('phone-connect-message').textContent = '正在请求连接 iPhone…';
  });
  $('disconnect').addEventListener('click', () => disconnect());
  $('fullscreen').addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      else $('status').textContent = '此浏览器不支持全屏';
    } catch (_) { $('status').textContent = '请通过浏览器菜单切换全屏'; }
  });
  document.addEventListener('fullscreenchange', () => {
    $('fullscreen').textContent = document.fullscreenElement ? '退出全屏' : '全屏';
    if (!document.fullscreenElement && launchPresentation?.immersive) {
      launchPresentation.immersive = false;
      launchPresentation.ownsFullscreen = false;
      $('topbar').hidden = false;
    }
    scheduleViewportReport();
  });
  // A separate layer prevents Android Chrome's native video gestures cancelling drags.
  for (const surface of [wcCanvas, touchSurface]) {
  surface.addEventListener('contextmenu', event => event.preventDefault());
  surface.addEventListener('pointerdown', event => {
    if (!live || pointers.size >= 2 || (event.pointerType === 'mouse' && event.button !== 0)) return;
    const point = mapPoint(event.clientX, event.clientY, getSurfaceRect(surface), width, height);
    if (!point) return;
    event.preventDefault();
    const id = [...pointers.values()].some(p => p.id === 0) ? 1 : 0;
    pointers.set(event.pointerId, { id, ...point });
    surface.setPointerCapture(event.pointerId);
    cancelMove();
    send({ type: 'touch', contacts: contacts() });
    scheduleMoveGate();
  });
  surface.addEventListener('pointermove', event => {
    const p = pointers.get(event.pointerId); if (!p) return;
    const point = mapPoint(event.clientX, event.clientY, getSurfaceRect(surface), width, height, true);
    if (!point) return;
    Object.assign(p, point);
    if (moveRaf !== null) {
      moveDirty = true;
    } else {
      send({ type: 'touch', contacts: contacts() }, 'move');
      scheduleMoveGate();
    }
  });
  function up(event) {
    const p = pointers.get(event.pointerId); if (!p) return;
    cancelMove();
    pointers.delete(event.pointerId);
    send({ type: 'touch', contacts: [...contacts(), { ...p, down: false }] });
  }
  surface.addEventListener('pointerup', up);
  surface.addEventListener('pointercancel', up);
  surface.addEventListener('lostpointercapture', up);
  }
  window.addEventListener('blur', release);
  document.addEventListener('visibilitychange', () => { if (document.hidden) release(); });
  window.addEventListener('pagehide', () => disconnect());
}
