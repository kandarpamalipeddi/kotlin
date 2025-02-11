// Auto-generated by org.jetbrains.kotlin.generators.tests.GenerateRangesCodegenTestData. DO NOT EDIT!
// WITH_RUNTIME



fun box(): String {
    val list1 = ArrayList<UInt>()
    for (i in (3u..5u).reversed()) {
        list1.add(i)
        if (list1.size > 23) break
    }
    if (list1 != listOf<UInt>(5u, 4u, 3u)) {
        return "Wrong elements for (3u..5u).reversed(): $list1"
    }

    val list2 = ArrayList<UInt>()
    for (i in (3u.toUShort()..5u.toUShort()).reversed()) {
        list2.add(i)
        if (list2.size > 23) break
    }
    if (list2 != listOf<UInt>(5u, 4u, 3u)) {
        return "Wrong elements for (3u.toUShort()..5u.toUShort()).reversed(): $list2"
    }

    val list3 = ArrayList<ULong>()
    for (i in (3uL..5uL).reversed()) {
        list3.add(i)
        if (list3.size > 23) break
    }
    if (list3 != listOf<ULong>(5u, 4u, 3u)) {
        return "Wrong elements for (3uL..5uL).reversed(): $list3"
    }

    return "OK"
}
