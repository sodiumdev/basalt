package basalt.lang;

public final class Pointer<T> {
    public T value;

    public Pointer(T value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "Pointer[value=" + STDLib.toString(value) + "]";
    }

    public static <T> Pointer<T> of(T value) {
        return new Pointer<>(value);
    }
}
