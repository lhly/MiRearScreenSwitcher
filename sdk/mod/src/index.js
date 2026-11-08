/** @typedef {{type:'column'|'row'|'box', children:UiNode[], gap?:number}} LayoutNode */
/** @typedef {{type:'text', text:string, style?:'title'|'body'|'caption'|'number'}} TextNode */
/** @typedef {{type:'button', label:string, action:{type:'event',name:string}}} ButtonNode */
/** @typedef {LayoutNode|TextNode|ButtonNode} UiNode */

const TOKEN = /^[a-z][A-Za-z0-9._-]{0,63}$/;

/** Host-origin event emitted by an accepted settings Save; reducers cannot send it. */
export const HOST_CONFIG_CHANGED_EVENT = 'host.configChanged';

/** Build only the public, declarative Mod descriptor; package signing is done by remrss pack. */
export function defineMod(descriptor) {
  if (!descriptor || descriptor.format !== 'remrss.mod' || descriptor.formatVersion !== 1) {
    throw new TypeError('Expected remrss.mod formatVersion 1');
  }
  return Object.freeze(structuredClone(descriptor));
}

/** A typed helper for named reducer events. It constructs data; it never reaches Android services. */
export const event = (name, payload = {}) => {
  if (!TOKEN.test(name)) throw new TypeError('Invalid event name');
  if (name === HOST_CONFIG_CHANGED_EVENT) throw new TypeError('host.configChanged is reserved for the host settings-save transaction');
  return Object.freeze({ type: name, ...structuredClone(payload) });
};

/** Stable public worker ABI. Bundlers must emit exactly globalThis.__remrss = {init, reduce}. */
export function createReducerModule({ init, reduce }) {
  if (typeof init !== 'function' || typeof reduce !== 'function') {
    throw new TypeError('A reducer module needs init(context) and reduce(context)');
  }
  return { init, reduce };
}

/**
 * Emit the isolated worker's single-file ABI from two self-contained functions.
 * Function source must not close over imports, module state, or host objects.
 */
export function buildReducerSource({ init, reduce, migrate }) {
  const module = createReducerModule({ init, reduce });
  if (migrate !== undefined && typeof migrate !== 'function') throw new TypeError('migrate must be a function when provided');
  const migration = migrate ? `, migrate: (${asFunctionExpression(migrate)})` : '';
  const source = `globalThis.__remrss = { init: (${asFunctionExpression(module.init)}), reduce: (${asFunctionExpression(module.reduce)})${migration} };\n`;
  if (/\b(import|export)\s/.test(source)) throw new TypeError('Reducer functions must be bundled before export');
  try { new Function(source); } catch (cause) { throw new TypeError('Reducer functions could not be emitted as valid JavaScript', { cause }); }
  return source;
}

function asFunctionExpression(fn) {
  const source = Function.prototype.toString.call(fn).trim();
  const methodPrefix = /^(async\s+)?[A-Za-z_$][\w$]*(\s*\()/;
  if (!methodPrefix.test(source)) return source;
  return source.replace(methodPrefix, (_, asyncPrefix = '', parameters = '(') => `${asyncPrefix}function${parameters}`);
}
