/** @typedef {{type:'column'|'row'|'box', children:UiNode[], gap?:number}} LayoutNode */
/** @typedef {{type:'text', text:string, style?:'title'|'body'|'caption'|'number'}} TextNode */
/** @typedef {{type:'button', label:string, action:{type:'event',name:string}}} ButtonNode */
/** @typedef {LayoutNode|TextNode|ButtonNode} UiNode */

const TOKEN = /^[a-z][A-Za-z0-9._-]{0,63}$/;

/** Host-origin event emitted by an accepted settings Save; reducers cannot send it. */
export const HOST_CONFIG_CHANGED_EVENT = 'host.configChanged';
export const HOST_NOTIFICATION_RESULT_EVENT = 'host.notificationResult';
const EFFECT_ID = /^[A-Za-z0-9_.-]{1,64}$/;
const REGISTERED_ACTION_ID = /^[a-f0-9]{48}$/;
const NOTIFICATION_ID = /^[A-Za-z0-9._:-]{1,64}$/;
const NOTIFICATION_PRIORITIES = new Set(['LOW', 'NORMAL', 'HIGH']);
const NOTIFICATION_PRIVACY = new Set(['STANDARD', 'HIDE_TITLE', 'HIDE_TEXT']);
function notificationText(value, field, maxLength, allowNewline = false) {
  if (typeof value !== 'string' || [...value].length > maxLength || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/u.test(value) || (!allowNewline && /[\r\n]/u.test(value))) {
    throw new TypeError(`Notification ${field} must be plain text with at most ${maxLength} characters`);
  }
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) throw new TypeError(`Notification ${field} must contain valid Unicode`);
      index++;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new TypeError(`Notification ${field} must contain valid Unicode`);
    }
  }
}
function utf8Length(value) {
  let bytes = 0;
  for (const character of value) {
    const codePoint = character.codePointAt(0);
    bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
  }
  return bytes;
}
function notificationPublish(request) {
  if (!request || typeof request !== 'object' || Array.isArray(request) || Object.keys(request).some(key => !['id', 'title', 'text', 'durationMs', 'priority', 'privacy'].includes(key))) {
    throw new TypeError('Notification request has unknown fields');
  }
  if (typeof request.id !== 'string' || !NOTIFICATION_ID.test(request.id)) throw new TypeError('Notification id must be 1-64 safe characters');
  notificationText(request.title, 'title', 80);
  if (request.title.length === 0) throw new TypeError('Notification title must not be empty');
  if (request.text !== undefined) notificationText(request.text, 'text', 240, true);
  if (request.durationMs !== undefined && (!Number.isSafeInteger(request.durationMs) || request.durationMs < 1000 || request.durationMs > 15000)) throw new TypeError('Notification durationMs must be an integer from 1000 to 15000');
  if (request.priority !== undefined && !NOTIFICATION_PRIORITIES.has(request.priority)) throw new TypeError('Notification priority must be LOW, NORMAL, or HIGH');
  if (request.privacy !== undefined && !NOTIFICATION_PRIVACY.has(request.privacy)) throw new TypeError('Notification privacy is invalid');
  const effect = { type: 'rear.notification.publish', notificationId: request.id, title: request.title };
  for (const key of ['text', 'durationMs', 'priority', 'privacy']) if (request[key] !== undefined) effect[key] = request[key];
  if (utf8Length(JSON.stringify(effect)) > 2048) throw new TypeError('Notification request must fit within 2 KiB UTF-8');
  return Object.freeze(effect);
}
export const host = Object.freeze({
  notification: Object.freeze({
    publish: notificationPublish,
    cancel(id) {
      if (typeof id !== 'string' || !NOTIFICATION_ID.test(id)) throw new TypeError('Notification id must be 1-64 safe characters');
      return Object.freeze({ type: 'rear.notification.cancel', notificationId: id });
    },
  }),
  schedule: Object.freeze({
    visible: Object.freeze({
      register(id, intervalMs) {
        if (typeof id !== 'string' || !EFFECT_ID.test(id) || !Number.isSafeInteger(intervalMs) || intervalMs < 1000) throw new TypeError('Visible schedule needs a valid id and intervalMs >= 1000');
        return Object.freeze({ type: 'schedule.visible.register', id, intervalMs });
      },
      registerDeadline(id, deadlineElapsedRealtimeMs) {
        if (typeof id !== 'string' || !EFFECT_ID.test(id) || !Number.isSafeInteger(deadlineElapsedRealtimeMs) || deadlineElapsedRealtimeMs < 0) throw new TypeError('Visible deadline needs a valid id and non-negative elapsedRealtime deadline');
        return Object.freeze({ type: 'schedule.visible.register', id, deadlineElapsedRealtimeMs });
      },
      cancel(id) {
        if (typeof id !== 'string' || !EFFECT_ID.test(id)) throw new TypeError('Visible schedule needs a valid id');
        return Object.freeze({ type: 'schedule.visible.cancel', id });
      },
    }),
  }),
  action: Object.freeze({
    invoke(ref) {
      if (!ref || typeof ref.id !== 'string' || !REGISTERED_ACTION_ID.test(ref.id) || ref.version !== 1) throw new TypeError('Invalid registered action reference');
      return Object.freeze({ type: 'action.invoke', ref: Object.freeze({ id: ref.id, version: 1 }) });
    },
  }),
});

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
  if (name === HOST_CONFIG_CHANGED_EVENT || name === HOST_NOTIFICATION_RESULT_EVENT) throw new TypeError(`${name} is reserved for host events`);
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
