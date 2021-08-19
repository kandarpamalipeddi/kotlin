// !API_VERSION: 1.5
// !LANGUAGE: +JvmRecordSupport
// JVM_TARGET: 15
// ENABLE_JVM_PREVIEW

<!NON_DATA_CLASS_JVM_RECORD!>@JvmRecord<!>
class BasicRecord(val x: String)

@JvmRecord
data class BasicDataRecord(val x: String)

<!NON_DATA_CLASS_JVM_RECORD!>@JvmRecord<!>
<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class BasicRecordWithSuperClass<!>(val x: String) : Record()

