import basalt.lang.STDLib

import java.lang.String

class Main {
    static fn main(args: String[]) {
        let asd: String? = null

        println(asd ?: "hi")
    }
}