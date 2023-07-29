import basalt.lang.STDLib

import java.lang.String
import java.lang.Object

class Main {
    fn doStuff() {
        println("hi")
    }

    static fn main(args: String[]) {
        let main = Main:new()

        main.doStuff()
    }
}