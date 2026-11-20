import test from 'node:test';
import assert from 'node:assert/strict';
import {
  pal,
  PlatformAbstractionLayer,
  ADVICE,
  PRESSURE_LEVEL,
  NET_MEDIA
} from '../../src/platform/pal/index.js';
import { loadLib, safeCall, safeCallAsync } from '../../src/platform/pal/ffi.js';
import {
  NoopPowerAdapter,
  NoopCacheAdapter,
  NoopMemoryAdapter,
  NoopNetworkAdapter,
  NoopLifecycleAdapter
} from '../../src/platform/pal/noop.js';
import {
  probe,
  classify,
  size,
  getTierConfig,
  TIERS,
  SLOT_SIZE_BYTES
} from '../../src/platform/tier/index.js';

test('PAL Step 1: Core contracts & Capability Registry initialize safely', async () => {
  assert.ok(pal, 'pal singleton should exist');
  assert.ok(pal.power, 'pal.power should exist');
  assert.ok(pal.cache, 'pal.cache should exist');
  assert.ok(pal.memory, 'pal.memory should exist');
  assert.ok(pal.network, 'pal.network should exist');
  assert.ok(pal.lifecycle, 'pal.lifecycle should exist');

  const caps = pal.capabilities;
  assert.ok(typeof caps.platform === 'string');
  assert.ok(typeof caps.power.available === 'boolean');
  assert.ok(typeof caps.cache.available === 'boolean');
  assert.ok(typeof caps.memory.available === 'boolean');
  assert.ok(typeof caps.network.available === 'boolean');
});

test('PAL Step 1: Power, Cache, and Memory operations never throw into core', async () => {
  // Power sleep inhibit & release
  const release = await pal.power.inhibitSleep('test-playback');
  assert.equal(typeof release, 'function');
  assert.doesNotThrow(() => release());

  // Cache advice with negative or invalid fd degrades gracefully
  assert.doesNotThrow(() => {
    pal.cache.advise(-1, 0, 1024, ADVICE.SEQUENTIAL);
    pal.cache.advise(9999, 0, 1024, ADVICE.DONTNEED);
    pal.cache.noCache(-1);
    pal.cache.syncRange(-1, 0, 1024);
  });

  // Memory sampling returns expected fields
  const sample = pal.memory.sample();
  assert.ok(['normal', 'warn', 'critical'].includes(sample.level));
  assert.ok(typeof sample.availBytes === 'number');
  assert.ok(typeof sample.timestamp === 'number');

  // Memory subscriber receives sample and unbinds cleanly
  let receivedSample = null;
  const unsubscribe = pal.memory.subscribe((s) => {
    receivedSample = s;
  });
  assert.equal(typeof unsubscribe, 'function');
  unsubscribe();
});

test('PAL Step 1: Network classification recognizes interface media types', async () => {
  const loopback = await pal.network.classify('lo');
  assert.equal(loopback.media, NET_MEDIA.LOOPBACK);

  const wifi = await pal.network.classify('wlan0');
  assert.equal(wifi.media, NET_MEDIA.WIFI);

  const cellular = await pal.network.classify('rmnet_data0');
  assert.equal(cellular.media, NET_MEDIA.CELLULAR);

  const wired = await pal.network.classify('eth0');
  assert.equal(wired.media, NET_MEDIA.WIRED);

  const defaultRoute = await pal.network.defaultRoute();
  assert.ok(typeof defaultRoute === 'string' && defaultRoute.length > 0);
});

test('PAL Step 1: Safe FFI and No-op fallbacks handle errors without crash', async () => {
  // Library load failure returns null without throwing
  const nonExistent = loadLib(['__non_existent_library_symbol_1234.so']);
  assert.equal(nonExistent, null);

  // safeCall catches exceptions
  const faultyFn = () => { throw new Error('Boom'); };
  const fallbackResult = safeCall(faultyFn, [], 'safe-fallback');
  assert.equal(fallbackResult, 'safe-fallback');

  // safeCallAsync catches rejections
  const faultyAsync = async () => { throw new Error('Async Boom'); };
  const asyncFallback = await safeCallAsync(faultyAsync, [], 'async-safe-fallback');
  assert.equal(asyncFallback, 'async-safe-fallback');

  // No-op adapter execution
  const noopPower = new NoopPowerAdapter();
  const noopRelease = await noopPower.inhibitSleep('test');
  assert.equal(typeof noopRelease, 'function');
  noopRelease();

  const noopMem = new NoopMemoryAdapter();
  const s = noopMem.sample();
  assert.ok(s.availBytes > 0);

  // Custom PAL with unsupported platform falls back cleanly
  const fallbackPAL = new PlatformAbstractionLayer({ platform: 'amigaos' });
  assert.equal(fallbackPAL.power.cap.available, false);
  assert.equal(fallbackPAL.cache.cap.available, false);
});

test('Tiering Step 2: Micro tier correctly classified and sized for constrained devices', () => {
  const GIB = 1024 * 1024 * 1024;

  // Case A: Low RAM phone (2 GB)
  const lowRamProbe = probe({
    totalMem: 2 * GIB,
    freeMem: 500 * 1024 * 1024,
    parallelism: 4,
    isAndroid: true
  });
  const classifiedA = classify(lowRamProbe);
  assert.equal(classifiedA.tier, TIERS.MICRO);

  // Case B: Constrained parallelism (dual-core)
  const dualCoreProbe = probe({
    totalMem: 8 * GIB,
    freeMem: 4 * GIB,
    parallelism: 2,
    isAndroid: false
  });
  const classifiedB = classify(dualCoreProbe);
  assert.equal(classifiedB.tier, TIERS.MICRO);

  // Case C: Android device with <= 1.5 GB available RAM
  const lowAvailAndroid = probe({
    totalMem: 4 * GIB,
    freeMem: 1.2 * GIB,
    parallelism: 8,
    isAndroid: true
  });
  const classifiedC = classify(lowAvailAndroid);
  assert.equal(classifiedC.tier, TIERS.MICRO);

  // Verify Sizing for Micro
  const sized = size(classifiedA, { freeDiskBytes: 10 * GIB });
  assert.equal(sized.bufferPool.maxSlots, 32); // 2 MiB
  assert.equal(sized.bufferPool.minSlots, 16); // 1 MiB
  assert.equal(sized.bufferPool.maxBytes, 32 * SLOT_SIZE_BYTES);
  assert.equal(sized.workers.heapCapMb, 64);
  assert.equal(sized.workers.idleTeardownSeconds, 10);
  assert.equal(sized.storage.tierCapMb, 256);
  assert.equal(sized.v8.semiSpaceMb, 8);
  assert.equal(sized.network.pacingPolicy, 'aggressive');

  // Dynamic slot clamp
  assert.equal(sized.bufferPool.computeSlots(1), 16);  // 1*4+8 = 12 -> clamped to min 16
  assert.equal(sized.bufferPool.computeSlots(4), 24);  // 4*4+8 = 24
  assert.equal(sized.bufferPool.computeSlots(20), 32); // 20*4+8 = 88 -> clamped to max 32
});

test('Tiering Step 2: Balanced tier correctly classified and caps Android', () => {
  const GIB = 1024 * 1024 * 1024;

  // Case A: 6 GB Linux laptop
  const laptopProbe = probe({
    totalMem: 6 * GIB,
    freeMem: 3 * GIB,
    parallelism: 4,
    isAndroid: false
  });
  const laptopClass = classify(laptopProbe);
  assert.equal(laptopClass.tier, TIERS.BALANCED);

  // Case B: High-end Android (12 GB RAM) - MUST be capped at Balanced
  const highEndAndroid = probe({
    totalMem: 12 * GIB,
    freeMem: 6 * GIB,
    parallelism: 8,
    isAndroid: true
  });
  const androidClass = classify(highEndAndroid);
  assert.equal(androidClass.tier, TIERS.BALANCED, 'Android must be capped at Balanced');

  // Sizing verification for Balanced
  const sized = size(laptopClass);
  assert.equal(sized.bufferPool.maxSlots, 64); // 4 MiB
  assert.equal(sized.bufferPool.minSlots, 16); // 1 MiB
  assert.equal(sized.workers.heapCapMb, 128);
  assert.equal(sized.workers.idleTeardownSeconds, 30);
  assert.equal(sized.storage.tierCapMb, 1024);
  assert.equal(sized.v8.semiSpaceMb, 16);
  assert.equal(sized.network.pacingPolicy, 'standard');
});

test('Tiering Step 2: Workstation tier correctly classified for high-spec desktop servers', () => {
  const GIB = 1024 * 1024 * 1024;

  const workstationProbe = probe({
    totalMem: 32 * GIB,
    freeMem: 20 * GIB,
    parallelism: 16,
    isAndroid: false,
    diskClass: 'nvme'
  });
  const wsClass = classify(workstationProbe);
  assert.equal(wsClass.tier, TIERS.WORKSTATION);

  const sized = size(wsClass, { freeDiskBytes: 500 * GIB });
  assert.equal(sized.bufferPool.maxSlots, 256); // 16 MiB
  assert.equal(sized.bufferPool.minSlots, 32);  // 2 MiB
  assert.equal(sized.workers.maxWorkers, 8);
  assert.equal(sized.workers.heapCapMb, 256);
  assert.equal(sized.workers.idleTeardownSeconds, 300);
  assert.equal(sized.storage.tierCapMb, 4096);
  assert.equal(sized.v8.semiSpaceMb, 32);
  assert.equal(sized.network.pacingPolicy, 'off');

  // computeBufferSlots bounds on workstation
  assert.equal(sized.bufferPool.computeSlots(1), 32);   // 1*4+8 = 12 -> clamped to min 32
  assert.equal(sized.bufferPool.computeSlots(20), 88);  // 20*4+8 = 88
  assert.equal(sized.bufferPool.computeSlots(100), 256); // 100*4+8 = 408 -> clamped to max 256
});

test('Tiering Step 2: getTierConfig single-call helper produces valid runtime options', () => {
  const cfg = getTierConfig();
  assert.ok(cfg.tier, 'tier must be present');
  assert.ok(cfg.bufferPool.maxSlots > 0);
  assert.ok(cfg.v8.nodeOptions.includes('--max-semi-space-size='));
  assert.ok(cfg.v8.nodeOptions.includes('--max-old-space-size='));
});
