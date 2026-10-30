package top.zainzh.rearscreen;

/** Optional ROM API: constrain the policy to our disposable trusted virtual display. */
final class RearCastResizePolicy {
    static final class Result {
        final Object config;
        final String mode;
        Result(Object config, String mode) { this.config = config; this.mode = mode; }
    }
    static Result build(Object builder, boolean requested) throws ReflectiveOperationException {
        String mode = requested ? "unsupported" : "disabled";
        boolean configured = false;
        if (requested) {
            try {
                builder.getClass().getMethod("setIgnoreActivitySizeRestrictions", boolean.class).invoke(builder, true);
                configured = true;
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // Keep the established density path on older ROMs or a denied optional API.
            }
        }
        Object config = builder.getClass().getMethod("build").invoke(builder);
        if (configured) {
            try {
                mode = Boolean.TRUE.equals(config.getClass().getMethod("isIgnoreActivitySizeRestrictions").invoke(config))
                        ? "unrestricted" : "system_disabled";
            } catch (ReflectiveOperationException | RuntimeException unavailable) { mode = "unverified"; }
        }
        return new Result(config, mode);
    }
}
