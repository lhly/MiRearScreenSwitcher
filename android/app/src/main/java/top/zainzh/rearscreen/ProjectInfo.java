package top.zainzh.rearscreen;

/** Maintainer and source locations for this independent fork. Modified 2026-10-15. */
final class ProjectInfo {
    static final String MAINTAINER = "冷火凉烟";
    static final String REPOSITORY = "https://github.com/lhly/MiRearScreenSwitcher";
    static final String RELEASES = REPOSITORY + "/releases";
    static final String RELEASE_PACKAGE = "top.zainzh.rearscreen";
    static final String VERSION_MANIFEST = "https://raw.githubusercontent.com/lhly/MiRearScreenSwitcher/main/updates/remrss/version.json";

    static String urlForAction(String action) {
        switch (action) {
            case "openProject": return REPOSITORY;
            case "openFeedback": return REPOSITORY + "/issues/new";
            case "openReleases": return RELEASES;
            case "openTutorial": return "https://docs.qq.com/doc/DVWxpT3hQdHNPR3Zy?dver=";
            case "openVersionSource": return REPOSITORY + "/tree/" + BuildConfig.SOURCE_TAG;
            case "openOriginalProject": return "https://github.com/AntiOblivionis/MiRearScreenSwitcher";
            case "openTesterProfile": return "https://www.coolapk.com/u/4279097";
            default: throw new IllegalArgumentException("Unknown project link: " + action);
        }
    }
}
