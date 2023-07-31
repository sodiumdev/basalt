import java.lang.String

class Person {
    private let _name: String = "Person#1"
    private let _age: int = 16

    getter fn name(): String {
        return this._name;
    }

    setter fn name(name: String) {
        this._name = name
    }

    getter fn age(): int {
        return this._age;
    }

    setter fn age(age: int) {
        this._age = age;
    }
}