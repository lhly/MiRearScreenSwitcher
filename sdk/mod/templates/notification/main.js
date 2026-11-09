globalThis.__remrss = {
  init() { return { status: '尚未发送通知', detail: '每次请求后会显示宿主的即时状态与真实呈现结果。' }; },
  reduce({ state, event }) {
    if (event.type === 'notification.publish') {
      return {
        state: { status: '正在发布', detail: '等待宿主队列结果…' },
        effects: [{ type: 'rear.notification.publish', notificationId: 'sample.notice', title: 'Mod 自有通知', text: '来自独立 ZIP Mod 的通知', durationMs: 8000 }],
      };
    }
    if (event.type === 'notification.update') {
      return {
        state: { status: '正在更新', detail: '同一个通知 ID 会更新当前 Mod 自己的通知。' },
        effects: [{ type: 'rear.notification.publish', notificationId: 'sample.notice', title: 'Mod 自有通知', text: '内容已更新', durationMs: 8000 }],
      };
    }
    if (event.type === 'notification.cancel') {
      return {
        state: { status: '正在取消', detail: '只取消本 Mod 的 sample.notice。' },
        effects: [{ type: 'rear.notification.cancel', notificationId: 'sample.notice' }],
      };
    }
    if (event.type === 'host.effectResult') {
      const { status, code } = event.payload;
      if (status === 'accepted') return { state: { status: '已进入宿主队列', detail: '入队不代表已显示；等待真实呈现回报。' } };
      if (status === 'suppressed') return { state: { status: '已被策略抑制', detail: code || '宿主策略阻止了显示。' } };
      return { state: { status: '请求未接受', detail: code || '宿主未能处理请求。' } };
    }
    if (event.type === 'host.notificationResult') {
      const { status, reason, endedBy } = event.payload;
      if (status === 'DISPLAYED') return { state: { status: '已实际显示', detail: '宿主确认通知窗口已完成布局并可见。' } };
      if (status === 'SUPPRESSED') return { state: { status: '已被策略抑制', detail: reason || '宿主策略阻止了显示。' } };
      if (status === 'ENDED') return { state: { status: '通知已结束', detail: endedBy || reason || '宿主结束了通知。' } };
      return { state: { status: '通知显示失败', detail: reason || endedBy || '宿主报告了渲染故障。' } };
    }
    return { state };
  },
};
