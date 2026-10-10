package top.mc506lw.rebar.pipez.endpoints

/**
 * 端点的搬运模式。用扳手右键循环切换。
 */
enum class EndpointMode(val id: String) {
    /** 不搬运。 */
    NONE("none"),

    /** 从相邻容器抽取，送进管道网。 */
    EXTRACT("extract"),

    /** 接收管道网送来的物品，放进相邻容器。 */
    INSERT("insert");

    val next: EndpointMode get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(value: Int): EndpointMode = entries.getOrNull(value) ?: NONE
    }
}
