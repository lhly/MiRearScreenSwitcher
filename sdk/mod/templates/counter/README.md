# Generic counter Mod

This neutral example shows the save contract without host-specific business rules: saving a new step emits the host-origin `host.configChanged` event, and its reducer updates `state.stepUsed` from the new config while receiving the previously committed value in `previousConfig`. The host validates and commits that state and config in one model revision. No separate “recalculate” action is needed.

The rear scene displays the committed count and step. It is a generic component/binding exercise, not a finished scorekeeping product. It requires `rear.scene@1` so an enabled, user-authorized package can open its rear scene; `rear.scene.keep_visible@1` is optional.
