// WITH_RUNTIME

import kotlin.coroutines.*

suspend fun foo() {
    Foo(
        arrayOf(
            bar()
        )
    )
}

suspend fun bar() = 5
var failure = false

class Foo(blobParts: Array<Int>) {
    init {
        if (blobParts[0] != 5) failure = true
    }
}

fun box(): String {
    ::foo.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })

    return if (!failure) "OK" else "FAILURE"
}
