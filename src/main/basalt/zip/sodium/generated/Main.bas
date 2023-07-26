import java.lang.String
import java.lang.Object

import basalt.lang.STDLib

import zip.sodium.generated.Library

public class Main {
    public static fn main(args: String[]): void {
        let a: Library = Library:new()

        private static fn child(): void {
            private static fn grandChild(): void {
                STDLib.println("Hello World!")
            }

            grandChild()
        }

        STDLib.println(a(1))
    }
}