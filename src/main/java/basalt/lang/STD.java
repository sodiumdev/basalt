package basalt.lang;

import java.lang.reflect.Array;

public class STD {
    public static void println(String s) {
        System.out.println(s);
    }

    public static void assertThat(boolean condition) {
        if (!condition)
            throw new AssertionError();
    }

    public static void exit() {
        exit(0);
    }

    public static void exit(int code) {
        System.exit(code);
    }

    public static void assertThat(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static String toString(Object a) {
        return a.toString();
    }

    public static String arrayToString(Object a) {
        if (a == null)
            return "null";

        if (!a.getClass().isArray())
            return a.toString();

        int iMax = Array.getLength(a) - 1;
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            b.append(arrayToString(Array.get(a, i)));
            if (i == iMax)
                return b.append(']').toString();
            b.append(", ");
        }
    }
}
