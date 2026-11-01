package top.zainzh.rearscreen;

import android.os.Binder;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps an owned test display to the debug Activity's logical rear role. No production fallback. */
final class RearDisplayTestIdentity implements AutoCloseable {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    RearDisplayTestIdentity(int logicalRearId) throws Exception {
        for (String name : new String[]{"remote", "trustedId", "trustedUnique", "selected", "lastRead"}) {
            Field field = RearDisplay.class.getDeclaredField(name); field.setAccessible(true); saved.put(field, field.get(null));
        }
        Binder binder = new Binder();
        ITaskService service = (ITaskService) Proxy.newProxyInstance(ITaskService.class.getClassLoader(),
                new Class<?>[]{ITaskService.class}, (proxy, method, args) -> {
                    if ("asBinder".equals(method.getName())) return binder;
                    if ("getRearDisplayState".equals(method.getName())) return "{\"selectedId\":" + logicalRearId
                            + ",\"selectedUnique\":\"instrumentation-fixture\",\"reason\":\"owned_test_display\"}";
                    if ("toString".equals(method.getName())) return "OwnedRearDisplayFixture";
                    throw new UnsupportedOperationException(method.getName());
                });
        field("remote").set(null, service); field("lastRead").setLong(null, 0);
    }
    private Field field(String name) { return saved.keySet().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow(); }
    @Override public void close() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }
}
