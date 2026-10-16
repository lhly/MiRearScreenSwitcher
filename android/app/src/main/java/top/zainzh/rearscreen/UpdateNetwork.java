package top.zainzh.rearscreen;

import android.content.Context;
import java.net.URI;

/** Routes public update requests only; manifest metadata keeps its original GitHub URLs. */
final class UpdateNetwork {
    static final String PROXY = "https://gh-proxy.com/";
    private static final String PREFERENCE = "update_proxy_enabled";

    static boolean proxyEnabled(Context context) {
        return context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE).getBoolean(PREFERENCE, false);
    }

    static void setProxyEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("mrss_settings", Context.MODE_PRIVATE).edit().putBoolean(PREFERENCE, enabled).apply();
    }

    static URI route(URI upstream, boolean proxy) {
        return proxy ? URI.create(PROXY + upstream) : upstream;
    }

    static URI redirect(URI upstream, String location, boolean proxy) {
        // Resolve relative asset links before wrapping: URI.resolve normalizes the nested https:// path.
        URI target = proxy && location.startsWith("/https://")
                ? URI.create(location.substring(1)) : upstream.resolve(location);
        // Unwrap only our exact HTTPS prefix. The downloader checks the upstream allowlist next.
        if (proxy && target.toString().startsWith(PROXY)) {
            return URI.create(target.toString().substring(PROXY.length()));
        }
        return target;
    }
}
