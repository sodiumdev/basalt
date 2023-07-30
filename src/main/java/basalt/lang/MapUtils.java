package basalt.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MapUtils {
    public static <K, V> void putIntoList(Map<K, List<V>> map, K key, V listValue) {
        List<V> value = map.get(key);
        if (value == null)
            value = new ArrayList<>();

        value.add(listValue);

        map.put(key, value);
    }
}
