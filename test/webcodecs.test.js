import test from 'node:test';
import assert from 'node:assert/strict';
import { annexBToAvcc, createWebCodecsReceiver, WEBCODECS_VIDEO_MAGIC } from '../common/src/main/assets/web/app.js';

globalThis.EncodedVideoChunk = class {
  constructor(init) { Object.assign(this, init); }
};

const AVCC = new Uint8Array([1, 0x64, 0, 0x20, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0, 0x20, 1, 0, 2, 0x68, 1]);

function configPacket({ codec = 1, width = 1280, height = 720, codecData = AVCC } = {}) {
  const buffer = new ArrayBuffer(20 + codecData.length);
  const view = new DataView(buffer);
  view.setUint32(0, WEBCODECS_VIDEO_MAGIC, true);
  view.setUint8(4, 1); view.setUint8(5, 110); view.setUint8(6, 0); view.setUint8(7, 0);
  view.setUint32(8, width, true); view.setUint32(12, height, true); view.setUint8(16, codec);
  new Uint8Array(buffer, 20).set(codecData);
  return buffer;
}

function framePacket({ keyframe = false, ptsUs = 1000, data = new Uint8Array([0, 0, 0, 1, 0x65, 1, 2]) } = {}) {
  const buffer = new ArrayBuffer(16 + data.length);
  const view = new DataView(buffer);
  view.setUint32(0, WEBCODECS_VIDEO_MAGIC, true);
  view.setUint8(4, 2); view.setUint8(5, 110); view.setUint8(6, keyframe ? 1 : 0); view.setUint8(7, 0);
  view.setBigUint64(8, BigInt(ptsUs), true);
  new Uint8Array(buffer, 16).set(data);
  return buffer;
}

function fakeDecoder() {
  const decoder = {
    state: 'unconfigured', decodeQueueSize: 0, config: null, chunks: [], closed: false,
    configure(config) { decoder.config = config; decoder.state = 'configured'; },
    decode(chunk) { decoder.chunks.push(chunk); },
    reset() { decoder.chunks = []; },
    close() { decoder.closed = true; decoder.state = 'closed'; },
  };
  return decoder;
}

function fixture() {
  const sent = [], draws = [], ready = [], errors = [], timers = new Map();
  let sequence = 0, clock = 0, decoder = null, created = 0;
  const context = { drawImage: (...args) => draws.push(args) };
  const canvas = { width: 0, height: 0, hidden: true, getContext: () => context };
  const receiver = createWebCodecsReceiver(canvas, {
    send: message => sent.push(message),
    onReady: () => ready.push(clock),
    onError: reason => errors.push(reason),
    createDecoder: init => {
      created += 1;
      decoder = fakeDecoder();
      decoder.init = init;
      return decoder;
    },
    now: () => clock,
    schedule(fn, delay) { const id = ++sequence; timers.set(id, { fn, delay }); return id; },
    cancel(id) { timers.delete(id); },
  });
  return {
    receiver, canvas, context, draws, sent, ready, errors, timers,
    get decoder() { return decoder; }, get created() { return created; },
    setTime(at) { clock = at; },
    tick(delay, at) {
      clock = at;
      const entry = [...timers].find(([, timer]) => timer.delay === delay);
      assert.ok(entry, `no timer for ${delay}`);
      timers.delete(entry[0]);
      entry[1].fn();
    },
  };
}

test('annex-b becomes length-prefixed avcc without losing a start code', () => {
  const out = annexBToAvcc(new Uint8Array([0, 0, 0, 1, 0x67, 9, 0, 0, 1, 0x68, 7]));
  assert.deepEqual([...out], [0, 0, 0, 2, 0x67, 9, 0, 0, 0, 2, 0x68, 7]);
  const untouched = new Uint8Array([1, 2, 3]);
  assert.equal(annexBToAvcc(untouched), untouched);
});

test('config is fed to WebCodecs and the first decoded frame becomes ready', () => {
  const f = fixture();
  assert.equal(f.receiver.receive(configPacket()), true);
  assert.equal(f.created, 1);
  assert.equal(f.decoder.config.codec, 'avc1.640020');
  assert.equal(f.decoder.config.codedWidth, 1280);
  assert.deepEqual([...f.decoder.config.description], [...AVCC]);
  assert.equal(f.receiver.active, false);

  assert.equal(f.receiver.receive(framePacket()), true);
  assert.equal(f.decoder.chunks.length, 0, 'deltas wait for the first keyframe');
  f.receiver.receive(framePacket({ keyframe: true }));
  assert.equal(f.decoder.chunks.length, 1);
  assert.equal(f.decoder.chunks[0].type, 'key');
  assert.deepEqual([...f.decoder.chunks[0].data], [0, 0, 0, 3, 0x65, 1, 2]);

  const frame = { displayWidth: 1280, displayHeight: 720, close() { this.closed = true; } };
  f.decoder.init.output(frame);
  assert.equal(frame.closed, true);
  assert.equal(f.receiver.active, true);
  assert.equal(f.canvas.width, 1280);
  assert.equal(f.canvas.height, 720);
  assert.equal(f.draws.length, 1);
  assert.deepEqual(f.ready, [0]);
  assert.ok(f.sent.some(message => message.type === 'webcodecs-keyframe'));
  f.receiver.close();
});

test('a decoder error reports one detailed fallback and stops the watchdog', () => {
  const f = fixture();
  f.receiver.receive(configPacket());
  f.decoder.init.error(new Error('hardware decoder rejected the stream'));
  assert.deepEqual(f.errors, ['hardware decoder rejected the stream']);
  const fallback = f.sent.at(-1);
  assert.equal(fallback.type, 'webcodecs-fallback');
  assert.equal(fallback.reason, 'hardware decoder rejected the stream');
  assert.equal(fallback.detail.reason, 'hardware decoder rejected the stream');
  assert.equal(fallback.detail.codec, 'H264');
  assert.equal(fallback.detail.codecString, 'avc1.640020');
  assert.equal(fallback.detail.descriptionBytes, AVCC.length);
  assert.equal(fallback.detail.decoderState, null, 'the failed decoder was closed');
  assert.deepEqual(fallback.detail.stats, {
    configs: 1, frames: 0, keyframes: 0, deltas: 0, dropped: 0, decoded: 0, errors: 1,
  });
  assert.deepEqual(fallback.detail.probes, [{ codec: 'avc1.640020', supported: 'unprobed' }]);
  assert.equal(fallback.detail.error.name, 'Error');
  assert.equal(fallback.detail.error.message, 'hardware decoder rejected the stream');
  assert.equal(fallback.detail.secureContext, null);
  assert.equal(typeof fallback.detail.userAgent, 'string');
  assert.equal(f.timers.size, 0);
  f.decoder.init.error(new Error('again'));
  assert.equal(f.errors.length, 1, 'only the first failure is reported');
  f.receiver.close();
});

test('a stalled decoder asks for keyframes and is closed without touching other binaries', () => {
  const f = fixture();
  assert.equal(f.receiver.receive(new Uint8Array([0xff, 0xd8, 0xff]).buffer), false);
  f.receiver.receive(configPacket());
  f.receiver.receive(framePacket({ keyframe: true }));
  f.tick(2000, 2000);
  assert.equal(f.sent.some(message => message.type === 'webcodecs-keyframe'), true);
  f.setTime(13000);
  f.tick(2000, 13000);
  assert.equal(f.errors.length, 0, 'before the first paint the receiver keeps asking');
  f.receiver.receive(framePacket({ keyframe: true }));
  const frame = { displayWidth: 1280, displayHeight: 720, close() {} };
  f.decoder.init.output(frame);
  f.setTime(14000);
  f.tick(2000, 14000);
  f.setTime(24001);
  f.tick(2000, 24001);
  assert.deepEqual(f.errors, ['no decoded frame']);
  assert.equal(f.receiver.active, false);
  f.receiver.close();
});

test('H.265 configuration tries the profile-specific codec strings first', () => {
  const hvcC = new Uint8Array(23);
  hvcC[0] = 1; hvcC[1] = 1; hvcC[12] = 120;
  const f = fixture();
  f.receiver.receive(configPacket({ codec: 2, codecData: hvcC }));
  assert.equal(f.decoder.config.codec.startsWith('hvc1.1.'), true);
  assert.deepEqual([...f.decoder.config.description], [...hvcC]);
  f.receiver.close();
});
