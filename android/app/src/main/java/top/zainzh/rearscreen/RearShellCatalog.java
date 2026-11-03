package top.zainzh.rearscreen;

/** Per-device public Xiaomi screenshot resources; no artwork is bundled. */
public final class RearShellCatalog {
    private static final String[] COLORS_17 = {"BK", "WH", "PU", "LG"};
    private static final String[] COLORS_18 = {"BK", "WH", "PK", "DB", "RD", "CY"};
    private RearShellCatalog() {}

    public static String[] colors(String device) {
        if ("pandora".equals(device) || "popsicle".equals(device)) return COLORS_17.clone();
        if ("hongkong".equals(device) || "madrid".equals(device)) return COLORS_18.clone();
        return new String[0];
    }

    public static long resourceId(String device, String color) {
        long[] ids;
        if ("popsicle".equals(device)) ids = new long[]{25000238622966048L, 25000242116493312L, 25000241377443904L, 25000240442638464L};
        else if ("pandora".equals(device)) ids = new long[]{24994890488217856L, 25005738663542848L, 25005736619999360L, 25005735444545792L};
        else if ("hongkong".equals(device)) ids = new long[]{26699815782645856L, 26699819854201056L, 26699817807446336L, 26699816735604896L, 26699818826727680L, 26738564433576160L};
        else if ("madrid".equals(device)) ids = new long[]{26703179066638656L, 26703184823255264L, 26703181217726464L, 26703180422316160L, 26703183772712960L, 26738831115288896L};
        else return 0;
        String[] palette = colors(device);
        for (int i = 0; i < palette.length; i++) if (palette[i].equals(color)) return ids[i];
        return 0;
    }

    public static String colorForDevice(String device, String savedColor) {
        // Preserve a saved choice on unsupported devices; their frame switch stays disabled.
        if (colors(device).length == 0 && isKnownColor(savedColor)) return savedColor;
        return resourceId(device, savedColor) != 0 ? savedColor : "BK";
    }

    static boolean isKnownColor(String color) {
        for (String value : COLORS_17) if (value.equals(color)) return true;
        for (String value : COLORS_18) if (value.equals(color)) return true;
        return false;
    }
}
