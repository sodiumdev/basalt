package basalt.lang;

public @interface Extension {
    ExtensionType type();
    Class<?> extendingClass();
}
