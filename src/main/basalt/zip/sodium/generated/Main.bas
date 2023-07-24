import java.lang.String
import java.lang.Object

import basalt.annotations.jvm.Public
import basalt.annotations.jvm.Final
import basalt.annotations.jvm.Static

import basalt.annotations.Magic

import basalt.lang.STD

import zip.sodium.generated.Library

@Public
class Main {
    @Public
    @Static
    fn main(args: String[]): void {
        let a: Library = Library:new()

        STD.assertThat(<boolean> (a + 1))
    }
}