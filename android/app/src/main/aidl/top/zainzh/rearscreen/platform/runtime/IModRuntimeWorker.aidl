package top.zainzh.rearscreen.platform.runtime;

import android.os.ParcelFileDescriptor;

/** Private host-to-isolated-worker contract. All payloads are bounded UTF-8 JSON strings. */
interface IModRuntimeWorker {
    String loadModule(in ParcelFileDescriptor sourceFd, int sourceLength);
    String init(String contextJson);
    String reduce(String contextJson);
    String migrate(String migrationContextJson);
    int workerPid();
    long workerPssKb();
    int budgetCapabilityFlags();
    String pauseBudgetThreadForTesting();
    void stallForWatchdogTesting();
    void crashForTesting();
    void close();
}
