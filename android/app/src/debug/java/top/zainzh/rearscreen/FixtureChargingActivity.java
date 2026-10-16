package top.zainzh.rearscreen;

/** 模拟器将虚拟副屏分配为2及以上；仅测试夹具将其映射为背屏角色。 */
public final class FixtureChargingActivity extends RearScreenChargingActivity {
    @Override protected int currentDisplayId() {
        int actual = super.currentDisplayId();
        int rear = getIntent().getIntExtra("fixtureRearDisplayId", 1);
        return actual == rear ? 1 : actual;
    }
}
