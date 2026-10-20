package top.zainzh.rearscreen;

import android.os.Binder;

/** adb shell/app_process probe of exactly the same engine used by TaskService. */
public final class RearInputShellProbe {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000 || args.length != 2) throw new SecurityException("Local shell probe only");
        int task = Integer.parseInt(args[1]);
        TaskService service = new TaskService();
        try {
            System.out.println("START " + service.startRearInputProxy(args[0], task, new Binder()));
            java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("Q")) break;
                String status = service.getRearInputProxyStatus();
                String id = new org.json.JSONObject(status).optString("id");
                if (line.equals("P")) service.renewRearInputProxyLease(id);
                System.out.println("STATUS " + status);
            }
        } finally { service.stopRearInputProxy(null); System.out.println("STOP " + service.getRearInputProxyStatus()); }
        System.exit(0);
    }
}
