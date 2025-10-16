package com.tgwgroup.MiRearScreenSwitcher;

import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 运行在Shizuku进程中的服务，具有shell权限
 */
public class TaskService extends ITaskService.Stub {
    private static final String TAG = "TaskService";

    @Keep
    public TaskService() {
        Log.d(TAG, "TaskService created with shell privileges!");
    }

    @Override
    public void destroy() {
        Log.d(TAG, "TaskService destroy");
        System.exit(0);
    }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {
            Log.d(TAG, "Getting current foreground app with shell privileges...");
            
            // 执行am stack list，在Shizuku进程中具有shell权限！
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            boolean inDisplayZero = false;
            String line;
            while ((line = reader.readLine()) != null) {
                // RootTask行：判断displayId
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }
                
                // taskId行（缩进的子行）
                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    // 解析:   taskId=1471: com.example.display_switcher/com.example.display_switcher.MainActivity
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    String taskId = line.substring(tidStart, tidEnd).trim();
                    
                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    String packageName = line.substring(pkgStart, pkgEnd).trim();
                    
                    // 跳过Launcher和应用自身
                    if (packageName.contains("launcher") || 
                        packageName.contains("miui.home") ||
                        packageName.equals("com.tgwgroup.MiRearScreenSwitcher")) {
                        continue;
                    }
                    
                    reader.close();
                    process.destroy();
                    
                    String result = packageName + ":" + taskId;
                    Log.d(TAG, "Found foreground app: " + result);
                    return result;
                }
            }
            
            int exitCode = process.waitFor();
            reader.close();
            
            Log.w(TAG, "No foreground app found (exit=" + exitCode + ")");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {
            Log.d(TAG, "Getting taskId for: " + packageName);
            
            // 执行am stack list，在Shizuku进程中具有shell权限！
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "am stack list");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()), 8192
            );
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName)) {
                    // 解析: taskId=1434: com.android.camera/...
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    
                    reader.close();
                    process.destroy();
                    
                    Log.d(TAG, "Found taskId: " + tid + " for " + packageName);
                    return tid;
                }
            }
            
            reader.close();
            process.waitFor();
            
            Log.w(TAG, "Package not found: " + packageName);
            return -1;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        try {
            Log.d(TAG, "Moving task " + taskId + " to display " + displayId);
            
            // 执行service call命令，在Shizuku进程中具有shell权限！
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "service call activity_task 50 i32 " + taskId + " i32 " + displayId);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            boolean success = (exitCode == 0);
            
            Log.d(TAG, "Move task result: " + success + " (exit=" + exitCode + ")");
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "Error moving task", e);
            return false;
        }
    }
}
