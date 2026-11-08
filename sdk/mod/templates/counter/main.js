// Generic counter example: a saved setting updates derived state atomically with config.
globalThis.__remrss = {
  init({ config }) { return { count: 0, stepUsed: config.step, previousStep: config.step }; },
  reduce({ state, config, previousConfig, event }) {
    if (event.type === 'host.configChanged') {
      return {
        state: { ...state, stepUsed: config.step, previousStep: previousConfig.step }
      };
    }
    if (event.type === 'counter.increment') {
      return { state: { ...state, count: state.count + config.step } };
    }
    if (event.type === 'counter.decrement') {
      return { state: { ...state, count: state.count - config.step } };
    }
    return { state };
  }
};
