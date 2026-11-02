package top.zainzh.rearscreen;

/** Boundaries shared by both privilege sources: external text is never shell syntax. */
final class PrivilegedShellArguments {
    private PrivilegedShellArguments() {}
    static void validateTargets(String packageName, String component) {
        if (packageName != null) VirtualCastRequest.packageName(packageName);
        if (component != null) VirtualCastRequest.component(component);
    }
    static String quote(String value) {
        if (value == null) value = "";
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in command argument");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    static String notification(String component, String packageName, String title, String text, long when) {
        VirtualCastRequest.component(component);
        return "am start -n " + quote(component) + " --es packageName " + quote(packageName)
                + " --es title " + quote(title) + " --es text " + quote(text) + " --el when " + when;
    }
    static String stopRecording(int pid, String output, int signal) {
        if (pid <= 0 || signal != 2 && signal != 15 || output == null
                || !output.matches("/storage/emulated/[0-9]+/Movies/ReMRSS_[0-9]{8}_[0-9]{6}(?:_[a-f0-9]{8})?\\.mp4"))
            throw new IllegalArgumentException("Invalid recording identity");
        // A stale PID must never signal an unrelated process after a recording ends by itself.
        return "if [ \"$(cat /proc/" + pid + "/comm 2>/dev/null)\" = screenrecord ] && "
                + "tr '\\000' '\\n' < /proc/" + pid + "/cmdline | grep -Fqx -- " + quote(output)
                + "; then kill -" + signal + " " + pid + "; else exit 1; fi";
    }
}
