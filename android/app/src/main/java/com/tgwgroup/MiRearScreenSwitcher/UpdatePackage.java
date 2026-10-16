package com.tgwgroup.MiRearScreenSwitcher;

import java.net.URI;
import org.json.JSONException;
import org.json.JSONObject;

/** Public release metadata; never contains authentication credentials. */
final class UpdatePackage {
    static final long MAX_SIZE = 256L * 1024 * 1024;
    final String tag, versionName, url, sha256, packageName;
    final int versionCode;
    final long size;

    UpdatePackage(String tag, String versionName, int versionCode, String url, String sha256, long size, String packageName) {
        this.tag = tag; this.versionName = versionName; this.versionCode = versionCode;
        this.url = url; this.sha256 = sha256; this.size = size; this.packageName = packageName;
    }

    static UpdatePackage parse(JSONObject manifest, String tag, int versionCode) throws JSONException {
        JSONObject apk = manifest.getJSONObject("apk");
        Object url = apk.get("url"), hash = apk.get("sha256"), size = apk.get("size"), name = apk.get("packageName");
        if (!(url instanceof String) || !(hash instanceof String) || !(name instanceof String)
                || !(size instanceof Integer || size instanceof Long)
                || !((String) hash).matches("[0-9a-f]{64}")
                || ((Number) size).longValue() <= 0 || ((Number) size).longValue() > MAX_SIZE
                || !ProjectInfo.RELEASE_PACKAGE.equals(name) || !validDownloadUrl((String) url, tag)) {
            throw new JSONException("Invalid APK metadata");
        }
        return new UpdatePackage(tag, tag.substring(1), versionCode, (String) url, (String) hash,
                ((Number) size).longValue(), (String) name);
    }

    static boolean validDownloadUrl(String url, String tag) {
        String prefix = ProjectInfo.RELEASES + "/download/" + tag + "/";
        return url.startsWith(prefix) && url.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk");
    }

    static boolean allowedRedirect(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && ("github.com".equalsIgnoreCase(host) || "release-assets.githubusercontent.com".equalsIgnoreCase(host)
                || "objects.githubusercontent.com".equalsIgnoreCase(host));
    }

    String toJson() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("versionName", versionName)
                    .put("versionCode", versionCode).put("tag", tag)
                    .put("apk", new JSONObject().put("url", url).put("sha256", sha256)
                            .put("size", size).put("packageName", packageName)).toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
