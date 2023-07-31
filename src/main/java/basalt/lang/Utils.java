package basalt.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Utils {
    public static <K> List<K> addToNullableList(List<K> list, K value) {
        if (list == null)
            list = new ArrayList<>();

        list.add(value);

        return list;
    }

    public static <K, V> void putIntoListInMap(Map<K, List<V>> map, K key, V listValue) {
        List<V> value = map.get(key);
        if (value == null)
            value = new ArrayList<>();

        value.add(listValue);

        map.put(key, value);
    }
}
