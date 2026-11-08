/** Capability names that the current host actually implements. A known future
 * capability is intentionally not part of this authoring contract yet. */
export type CapabilityName = 'rear.scene' | 'rear.scene.keep_visible' | 'data.battery' | 'data.systemMetrics' | 'model.local' | 'ui.native' | 'ui.dialog';
export interface CapabilityRequirement { name: CapabilityName; minimumVersion: 1 }
export interface CapabilityDeclaration { required: CapabilityRequirement[]; optional: CapabilityRequirement[] }

export const HOST_CONFIG_CHANGED_EVENT: 'host.configChanged';
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
  env: Readonly<Record<string, unknown>>;
}
export interface ModResult<S = Record<string, unknown>> { state: S; effects?: readonly ModEffect[] }
/** The host validates each effect type/capability/source before commit and dispatch. */
export type ModEffect = {
  /** Requires a fresh user activation; host.configChanged cannot mint one. */
  type: 'rear.open';
  scene: 'rear';
  region: 'CAMERA_SAFE';
  keepVisible: boolean;
};
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
    env: Readonly<Record<string, unknown>>;
  }): { state: S | null; config: C };
}

export type BindingPath = `state.${string}` | `config.${string}` | 'data.battery.level' | 'data.battery.scale' | 'data.battery.charging' |
  'data.systemMetrics.cpu.utilizationPercent' | 'data.systemMetrics.memory.availableBytes' | 'data.systemMetrics.memory.totalBytes';
export type BoundValue = { bind: BindingPath };
export type UiValue = string | number | boolean | BoundValue;
export type EventAction = { type: 'event'; name: string };
export type UiStyle = 'title' | 'body' | 'caption' | 'number' | 'displayNumber';

export type UiNode =
  | { type: 'layout.column' | 'layout.row' | 'layout.box'; children: UiNode[]; gap?: number }
  | { type: 'ui.text' | 'ui.number'; value: UiValue; style?: UiStyle }
  | { type: 'ui.button'; label: string; onPress: EventAction; enabled?: boolean; hint?: string }
  | { type: 'ui.image'; asset: `assets/${string}`; alt?: string }
  | { type: 'ui.divider' }
  | { type: 'ui.progress'; value: number | BoundValue; min: number; max: number }
  | { type: 'settings.row'; label: string; value?: BoundValue; hint?: string; onPress?: EventAction }
  | { type: 'settings.input'; label: string; value: { bind: `config.${string}` }; hint?: string; placeholder?: string }
  | { type: 'settings.number'; label: string; value: { bind: `config.${string}` }; hint?: string; min: number; max: number }
  | { type: 'settings.toggle'; label: string; value: { bind: `config.${string}` }; hint?: string }
  | { type: 'settings.select'; label: string; value: { bind: `config.${string}` }; hint?: string; options: Array<{ label: string; value: string | number | boolean }> }
  | { type: 'settings.save' }
  | { type: 'settings.discard' }
  | { type: 'ui.confirm'; label: string; title: string; message: string; onConfirm: EventAction };

export function defineMod<T extends Record<string, unknown>>(descriptor: T): Readonly<T>;
export function event<T extends Record<string, unknown> = Record<string, unknown>>(name: string, payload?: T): Readonly<{ type: string } & T>;
export function createReducerModule<S, C>(reducer: ModReducer<S, C>): ModReducer<S, C>;
export function buildReducerSource<S, C>(reducer: ModReducer<S, C>): string;
