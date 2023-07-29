package basalt.lang;

import java.lang.reflect.Array;

public class STDLib {
    @Inline
    public static void println(String s) {
        System.out.println(s);
    }

    @Inline
    public static void assertThat(boolean condition) {
        if (!condition)
            throw new AssertionError();
    }

    @Inline
    public static void exit() {
        exit(0);
    }

    @Inline
    public static void exit(int code) {
        System.exit(code);
    }

    @Inline
    public static void assertThat(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    @Inline
    public static String toString(Object a) {
        if (a == null)
            return "null";
        if (a.getClass().isArray())
            return arrayToString(a);

        return a.toString();
    }

    @Inline
    public static String arrayToString(Object a) {
        if (a == null)
            return "null";
        if (!a.getClass().isArray())
            return toString(a);

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
