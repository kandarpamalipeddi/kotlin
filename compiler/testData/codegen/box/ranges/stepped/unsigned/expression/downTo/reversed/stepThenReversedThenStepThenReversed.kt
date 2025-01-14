// Auto-generated by GenerateSteppedRangesCodegenTestData. Do not edit!
// WITH_RUNTIME
import kotlin.test.*

fun box(): String {
    val uintList = mutableListOf<UInt>()
    val uintProgression = 10u downTo 1u
    for (i in ((uintProgression step 2).reversed() step 3).reversed()) {
        uintList += i
    }
    assertEquals(listOf(8u, 5u, 2u), uintList)

    val ulongList = mutableListOf<ULong>()
    val ulongProgression = 10uL downTo 1uL
    for (i in ((ulongProgression step 2L).reversed() step 3L).reversed()) {
        ulongList += i
    }
    assertEquals(listOf(8uL, 5uL, 2uL), ulongList)

    return "OK"
}