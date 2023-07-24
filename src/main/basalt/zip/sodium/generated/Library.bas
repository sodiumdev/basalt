import java.lang.Object

import basalt.annotations.jvm.Public
import basalt.annotations.jvm.Private

import basalt.annotations.Magic

@Public
class Library {
    @Private
    fn do_shit(i: int): int
        return i + 2;

    @Public
    @Magic(type="SUBSCRIPT")
    fn(index: Object): int
        return do_shit(<int> index);

    @Public
    @Magic(type="CALL")
    fn(i: int): int
        return do_shit(i);

    @Public
    @Magic(type="ADD")
    fn(i: int): int
        return do_shit(i);

    @Public
    @Magic(type="SUBTRACT")
    fn(i: int): int
        return do_shit(i);
}