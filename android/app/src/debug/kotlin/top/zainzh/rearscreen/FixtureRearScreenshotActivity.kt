package top.zainzh.rearscreen

/** Preview-only host for emulator UI QA; no rear display or capture capability is simulated. */
class FixtureRearScreenshotActivity : RearScreenshotActivity() {
    public override fun templateDevice() = intent.getStringExtra("template_device") ?: "popsicle"
}
