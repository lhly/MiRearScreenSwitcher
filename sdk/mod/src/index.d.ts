/** Capability names that the current host actually implements. A known future
 * capability is intentionally not part of this authoring contract yet. */
export type CapabilityName = 'rear.scene' | 'rear.scene.keep_visible' | 'rear.notification' | 'data.battery' | 'data.systemMetrics' | 'model.local' | 'ui.native' | 'ui.dialog' | 'action.invoke' | 'schedule.visible' | 'clock.snapshot';
export interface CapabilityRequirement { name: CapabilityName; minimumVersion: 1 }
export interface CapabilityDeclaration { required: CapabilityRequirement[]; optional: CapabilityRequirement[] }

export const HOST_CONFIG_CHANGED_EVENT: 'host.configChanged';
export const HOST_NOTIFICATION_RESULT_EVENT: 'host.notificationResult';
export interface ModEvent {
  type: string;
  payload?: Record<string, unknown>;
  /** Host-assigned metadata is present for accepted business and config-change events. */
  eventId?: string;
  revision?: number;
  logicalSessionId?: string;
}
export interface HostConfigChangedEvent extends ModEvent {
  type: typeof HOST_CONFIG_CHANGED_EVENT;
  payload: Record<string, never>;
  eventId: string;
  revision: number;
  logicalSessionId: string;
}
export interface ModContext<S = Record<string, unknown>, C = Record<string, unknown>> {
  state: S;
  /** For host.configChanged, this is the newly saved, schema-validated config. */
  config: C;
  /** Present only on host.configChanged; the last committed config before this save. */
  previousConfig?: C;
  event?: ModEvent;
  env: Readonly<{ clock: { wallTimeMs: number; elapsedRealtimeMs: number; timezoneId: string; bootId: null }; [key: string]: unknown }>;
}
export interface ModResult<S = Record<string, unknown>> { state: S; effects?: readonly ModEffect[] }
/** The host validates each effect type/capability/source before commit and dispatch. */
export type ModEffect = {
  /** Requires a fresh user activation; host.configChanged cannot mint one. */
  type: 'rear.open';
  scene: 'rear';
  region: 'CAMERA_SAFE';
  keepVisible: boolean;
} | { type: 'schedule.visible.register'; id: string; intervalMs: number; deadlineElapsedRealtimeMs?: number }
  | { type: 'schedule.visible.register'; id: string; deadlineElapsedRealtimeMs: number; intervalMs?: number }
  | { type: 'schedule.visible.cancel'; id: string }
  | { type: 'action.invoke'; ref: { id: string; version: number } }
  | { type: 'rear.notification.publish'; id?: string; notificationId: string; title: string; text?: string; durationMs?: number; priority?: NotificationPriority; privacy?: NotificationPrivacy }
  | { type: 'rear.notification.cancel'; id?: string; notificationId: string };
export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH';
export type NotificationPrivacy = 'STANDARD' | 'HIDE_TITLE' | 'HIDE_TEXT';
export interface NotificationRequest { id: string; title: string; text?: string; durationMs?: number; priority?: NotificationPriority; privacy?: NotificationPrivacy }
export interface HostEffectResultEvent extends ModEvent {
  type: 'host.effectResult';
  /** Original declarative id or host-generated id. */
  payload: { effectId: string; requestEventId: string; status: 'accepted' | 'suppressed' | 'rejected'; code?: string };
}
export type NotificationEndReason = 'TIMEOUT' | 'REPLACED' | 'CANCELLED' | 'OWNER_CLOSED' | 'OWNER_DIED' | 'AUTH_REVOKED' | 'GLOBAL_DISABLED' | 'PRIVACY_POLICY' | 'DND' | 'COVERED' | 'DISPLAY_UNAVAILABLE' | 'SCENE_TAKEOVER' | 'SERVICE_STOPPED' | 'RENDER_FAILED' | 'HOST_ERROR';
export interface HostNotificationResultEvent extends ModEvent {
  type: 'host.notificationResult';
  /** Emitted after render evidence or an asynchronous owner/policy event. */
  payload: { effectId: string; requestEventId: string; notificationId: string; status: 'DISPLAYED' | 'SUPPRESSED' | 'ENDED' | 'FAILED'; reason?: string; endedBy?: NotificationEndReason };
}
export interface HostScheduleTickEvent extends ModEvent {
  type: 'host.schedule.tick';
  payload: { id: string; scheduledElapsedRealtimeMs: number; actualElapsedRealtimeMs: number; missedCount: number; causes: readonly ('interval' | 'deadline')[] };
}
export interface HostVisibilityChangedEvent extends ModEvent {
  type: 'host.visibility.changed';
  payload: { visible: boolean; epoch: number };
}
export interface ModReducer<S = Record<string, unknown>, C = Record<string, unknown>> {
  init(context: Pick<ModContext<S, C>, 'config' | 'env'>): S;
  reduce(context: ModContext<S, C>): ModResult<S>;
  /** Return state:null only when context.state is null; host rejects object-to-null migration. */
  migrate?(context: {
    /** null only when this installed package has never been activated and init has not run. */
    state: S | null;
    config: C;
    fromVersion: number;
    toVersion: number;
    env: Readonly<{ clock: { wallTimeMs: number; elapsedRealtimeMs: number; timezoneId: string; bootId: null }; [key: string]: unknown }>;
  }): { state: S | null; config: C };
}

export type BindingPath = `state.${string}` | `config.${string}` | 'data.battery.level' | 'data.battery.scale' | 'data.battery.charging' |
  'data.systemMetrics.cpu.utilizationPercent' | 'data.systemMetrics.memory.availableBytes' | 'data.systemMetrics.memory.totalBytes';
export type BoundValue = { bind: BindingPath };
export type UiValue = string | number | boolean | BoundValue;
export type RegisteredActionRef = { id: string; version: 1 };
export type RegisteredActionBinding = { bind: `config.${string}` };
export type EventAction = { type: 'event'; name: string } | { type: 'registeredAction'; ref: RegisteredActionRef | RegisteredActionBinding };
export type UiStyle = 'title' | 'body' | 'caption' | 'number' | 'displayNumber';

export type UiTone = 'primary' | 'secondary' | 'success' | 'warning' | 'error';
export type UiFormat = 'text' | 'integer' | 'decimal' | 'percent' | 'duration';
export type VisibilityCondition = { bind: BindingPath; equals: string | number | boolean };
export type UiNode =
  | { type: 'layout.column' | 'layout.row' | 'layout.box'; children: UiNode[]; gap?: number; horizontalAlignment?: 'start' | 'center' | 'end' | 'spaceBetween'; verticalAlignment?: 'top' | 'center' | 'bottom' }
  | { type: 'ui.text' | 'ui.number'; value: UiValue; style?: UiStyle; format?: UiFormat; tone?: UiTone; visibleWhen?: VisibilityCondition }
  | { type: 'ui.list'; items: BoundValue; maxItems: number; style?: 'body' | 'caption'; emptyText?: string; format?: UiFormat; visibleWhen?: VisibilityCondition }
  | { type: 'ui.icon'; name: 'add' | 'remove' | 'check' | 'close' | 'timer' | 'trophy' | 'info' | 'warning'; label: string; tone?: UiTone; visibleWhen?: VisibilityCondition }
  | { type: 'ui.deadlineText'; value: BoundValue; format?: 'duration'; visibleWhen?: VisibilityCondition }
  | { type: 'ui.button'; label: string; onPress: EventAction; enabled?: boolean; hint?: string; visibleWhen?: VisibilityCondition }
  | { type: 'ui.image'; asset: `assets/${string}`; alt?: string; visibleWhen?: VisibilityCondition }
  | { type: 'ui.divider'; visibleWhen?: VisibilityCondition }
  | { type: 'ui.progress'; value: number | BoundValue; min: number; max: number; visibleWhen?: VisibilityCondition }
  | { type: 'settings.row'; label: string; value?: BoundValue; hint?: string; onPress?: EventAction; visibleWhen?: VisibilityCondition }
  | { type: 'settings.input'; label: string; value: { bind: `config.${string}` }; hint?: string; placeholder?: string; visibleWhen?: VisibilityCondition }
  | { type: 'settings.action'; label: string; value: { bind: `config.${string}` }; hint?: string; visibleWhen?: VisibilityCondition }
  | { type: 'settings.number'; label: string; value: { bind: `config.${string}` }; hint?: string; min: number; max: number }
  | { type: 'settings.toggle'; label: string; value: { bind: `config.${string}` }; hint?: string }
  | { type: 'settings.select'; label: string; value: { bind: `config.${string}` }; hint?: string; options: Array<{ label: string; value: string | number | boolean }> }
  | { type: 'settings.save' }
  | { type: 'settings.discard' }
  | { type: 'ui.confirm'; label: string; title: string; message: string; onConfirm: EventAction };

export function defineMod<T extends Record<string, unknown>>(descriptor: T): Readonly<T>;
export const host: {
  notification: { publish(request: NotificationRequest): ModEffect; cancel(id: string): ModEffect };
  schedule: { visible: { register(id: string, intervalMs: number): ModEffect; registerDeadline(id: string, deadlineElapsedRealtimeMs: number): ModEffect; cancel(id: string): ModEffect } };
  action: { invoke(ref: RegisteredActionRef): ModEffect };
};
export function event<T extends Record<string, unknown> = Record<string, unknown>>(name: string, payload?: T): Readonly<{ type: string } & T>;
export function createReducerModule<S, C>(reducer: ModReducer<S, C>): ModReducer<S, C>;
export function buildReducerSource<S, C>(reducer: ModReducer<S, C>): string;
