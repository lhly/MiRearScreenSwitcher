package top.zainzh.rearscreen;

/** 为模拟器虚拟副屏映射逻辑背屏角色，不进入正式包。 */
public final class FixtureNotificationActivity extends RearScreenNotificationActivity {
    @Override protected int currentDisplayId() {
        int actual = super.currentDisplayId();
        return actual == getIntent().getIntExtra("fixtureRearDisplayId", 1) ? 1 : actual;
    }
}
