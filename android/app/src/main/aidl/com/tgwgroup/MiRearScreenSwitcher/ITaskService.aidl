package com.tgwgroup.MiRearScreenSwitcher;

interface ITaskService {
    void destroy() = 16777114;  // Shizuku required
    
    /**
     * 获取当前前台应用的包名和taskId
     * @return "package:taskId" 格式
     */
    String getCurrentForegroundApp() = 1;
    
    /**
     * 通过包名获取taskId
     * @param packageName 包名
     * @return taskId，失败返回-1
     */
    int getTaskIdByPackage(String packageName) = 2;
    
    /**
     * 移动任务到指定显示屏
     * @param taskId 任务ID
     * @param displayId 显示屏ID (0=主屏, 1=背屏)
     * @return 是否成功
     */
    boolean moveTaskToDisplay(int taskId, int displayId) = 3;
}

