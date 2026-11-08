package top.zainzh.rearscreen;

/** Synchronous, one-shot authorization check immediately before an owned task focus command. */
interface ISceneTaskActionGate {
    boolean isCurrent();
}
