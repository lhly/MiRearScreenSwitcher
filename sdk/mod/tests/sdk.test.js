import test from 'node:test';
import assert from 'node:assert/strict';
import { buildReducerSource, createReducerModule, defineMod, event, host, HOST_CONFIG_CHANGED_EVENT } from '../src/index.js';

test('public schedule and registered action helpers create bounded host effects', () => {
  assert.deepEqual(host.schedule.visible.register('clock.phase', 1000), {
    type: 'schedule.visible.register', id: 'clock.phase', intervalMs: 1000,
  });
  assert.deepEqual(host.schedule.visible.cancel('clock.phase'), { type: 'schedule.visible.cancel', id: 'clock.phase' });
  assert.deepEqual(host.schedule.visible.registerDeadline('timer.end', 10000), { type: 'schedule.visible.register', id: 'timer.end', deadlineElapsedRealtimeMs: 10000 });
  const actionId = `0${'a'.repeat(47)}`;
  assert.deepEqual(host.action.invoke({ id: actionId, version: 1 }), { type: 'action.invoke', ref: { id: actionId, version: 1 } });
  assert.throws(() => host.schedule.visible.register('bad', 999), /intervalMs/);
  assert.throws(() => host.schedule.visible.registerDeadline('bad', -1), /elapsedRealtime/);
  assert.throws(() => host.action.invoke({ id: 'intent://unsafe', version: 1 }), /reference/);
  assert.throws(() => host.action.invoke({ id: 'abc', version: 1 }), /reference/);
  assert.throws(() => host.action.invoke({ id: 'A'.repeat(48), version: 1 }), /reference/);
});

test('builder emits the isolated runtime global ABI', () => {
  const source = buildReducerSource({
    init: () => ({ count: 0 }),
    reduce: ({ state, event }) => event.type === 'counter.add'
      ? { state: { count: state.count + event.amount } }
      : { state },
  });
  assert.match(source, /globalThis\.__remrss/);
  assert.doesNotMatch(source, /\bexport\b/);
});

test('migration is optional and its ABI returns only new data', () => {
  const source = buildReducerSource({
    init: () => ({ count: 0 }),
    reduce: ({ state }) => ({ state }),
    migrate: ({ state, config }) => ({ state: { count: state.oldCount }, config }),
  });
  assert.match(source, /migrate:/);
  assert.match(source, /globalThis\.__remrss/);
});

test('builder accepts ordinary object method syntax and emits executable JavaScript', () => {
  const source = buildReducerSource({
    init(context) { return { count: context.config.start }; },
    reduce(context) { return { state: { count: context.state.count + 1 } }; },
    migrate(context) { return { state: context.state, config: context.config }; },
  });
  const compiled = new Function(`${source}; return globalThis.__remrss;`)();
  assert.deepEqual(compiled.init({ config: { start: 4 } }), { count: 4 });
  assert.deepEqual(compiled.reduce({ state: { count: 4 } }), { state: { count: 5 } });
  assert.deepEqual(compiled.migrate({ state: { count: 5 }, config: {} }), { state: { count: 5 }, config: {} });
});

test('migration keeps an uninitialized model null until first activation', () => {
  const source = buildReducerSource({
    init() { return { score: 0 }; },
    reduce({ state }) { return { state }; },
    migrate({ state, config }) {
      return state === null
        ? { state: null, config: { ...config, team: 'A' } }
        : { state: { ...state, label: 'A' }, config: { ...config, team: 'A' } };
    },
  });
  const compiled = new Function(`${source}; return globalThis.__remrss;`)();
  assert.deepEqual(compiled.migrate({ state: null, config: {}, fromVersion: 1, toVersion: 2, env: {} }), {
    state: null,
    config: { team: 'A' },
  });
  assert.deepEqual(compiled.init({ config: { team: 'A' }, env: {} }), { score: 0 });
});

test('builder keeps function declarations and arrows as executable expressions', () => {
  const source = buildReducerSource({
    init: function init() { return { n: 1 }; },
    reduce: ({ state }) => ({ state }),
  });
  const compiled = new Function(`${source}; return globalThis.__remrss;`)();
  assert.deepEqual(compiled.init(), { n: 1 });
  assert.deepEqual(compiled.reduce({ state: { n: 1 } }), { state: { n: 1 } });
});

test('event builder keeps data separate from host access', () => {
  assert.deepEqual(event('counter.add', { amount: 2 }), { type: 'counter.add', amount: 2 });
  assert.throws(() => event('Bad Event'));
  assert.equal(HOST_CONFIG_CHANGED_EVENT, 'host.configChanged');
  assert.throws(() => event(HOST_CONFIG_CHANGED_EVENT), /reserved for the host settings-save transaction/);
});

test('host config change can derive UI state from new and previous config', () => {
  const source = buildReducerSource({
    init({ config }) { return { stepUsed: config.step }; },
    reduce({ state, config, previousConfig, event }) {
      return event.type === 'host.configChanged'
        ? { state: { ...state, stepUsed: config.step, previousStep: previousConfig.step } }
        : { state };
    },
  });
  const reducer = new Function(`${source}; return globalThis.__remrss;`)();
  assert.deepEqual(reducer.reduce({
    state: { stepUsed: 1 }, config: { step: 4 }, previousConfig: { step: 1 },
    event: { type: HOST_CONFIG_CHANGED_EVENT },
  }), { state: { stepUsed: 4, previousStep: 1 } });
});

test('defineMod rejects a wrong package protocol', () => {
  assert.throws(() => defineMod({ format: 'remodule', formatVersion: 1 }));
  assert.equal(typeof createReducerModule({ init: () => ({}), reduce: ({state}) => ({state}) }).reduce, 'function');
});
