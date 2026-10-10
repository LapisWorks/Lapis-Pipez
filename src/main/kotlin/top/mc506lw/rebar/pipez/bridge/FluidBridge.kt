package top.mc506lw.rebar.pipez.bridge

import io.github.pylonmc.rebar.fluid.VirtualFluidPoint
import java.lang.reflect.Method
import java.util.UUID

/**
 * 访问 Rebar 流体网络的**反射适配层**。
 *
 * ## 为什么需要反射
 *
 * `io.github.pylonmc.rebar.fluid.FluidManager` 在 Kotlin 层是 `internal object`，
 * `FluidPipePlacementService` 也是 `internal`。Kotlin 编译器**不允许**第三方插件使用它们：
 *
 * ```
 * Cannot access 'object FluidManager : Any': it is internal in file.
 * ```
 *
 * 也就是说：**Rebar 的流体网络对第三方插件是封闭的**，公开 API 只能建"点"、不能连"点"。
 *
 * 但字节码层面 `FluidManager` 是一个普通的 `public final class`，
 * 方法都是 `public static final`，而且**没有名字混淆**（`add` / `connect` / `disconnect` …
 * 都是原名，不是 `add$rebar`）。所以反射可以干净地调到它们。
 *
 * ## 代价（必须知道）
 *
 * - 走的是**框架明确标注为非公开的内部 API**。Rebar 是 0.x，任何一次发布都可能改名或改结构。
 * - 所以这里做了三件事：① 全部反射集中在这一个文件；② 启动时一次性解析并缓存 `Method`；
 *   ③ 任何一步失败都**只把流体功能关掉**，绝不抛异常影响其他功能。
 *
 * 想彻底不用反射就把 `config.yml` 的 `fluid.use-reflection` 设为 `false`。
 */
object FluidBridge {

    private const val MANAGER_CLASS = "io.github.pylonmc.rebar.fluid.FluidManager"

    private var addMethod: Method? = null
    private var removeMethod: Method? = null
    private var connectMethod: Method? = null
    private var disconnectMethod: Method? = null
    private var getAllConnectedMethod: Method? = null
    private var setFluidPerSecondMethod: Method? = null

    /** 反射是否可用。启动时解析一次，之后只读。 */
    var available: Boolean = false
        private set

    /** 解析失败的原因，供启动日志使用。 */
    var failureReason: String? = null
        private set

    /**
     * 在 `onEnable` 里调用一次。
     *
     * @return 是否成功（false 时流体功能整体禁用，其余功能不受影响）
     */
    fun initialize(): Boolean {
        return try {
            val manager = Class.forName(MANAGER_CLASS)
            addMethod = manager.getMethod("add", VirtualFluidPoint::class.java)
            removeMethod = manager.getMethod("remove", VirtualFluidPoint::class.java)
            connectMethod = manager.getMethod(
                "connect", VirtualFluidPoint::class.java, VirtualFluidPoint::class.java
            )
            disconnectMethod = manager.getMethod(
                "disconnect", VirtualFluidPoint::class.java, VirtualFluidPoint::class.java
            )
            getAllConnectedMethod = manager.getMethod("getAllConnected", VirtualFluidPoint::class.java)
            setFluidPerSecondMethod = manager.getMethod("setFluidPerSecond", UUID::class.java, Double::class.javaPrimitiveType)
            available = true
            failureReason = null
            true
        } catch (t: Throwable) {
            available = false
            failureReason = "${t::class.java.simpleName}: ${t.message}"
            false
        }
    }

    fun add(point: VirtualFluidPoint) {
        invoke(addMethod, point)
    }

    fun remove(point: VirtualFluidPoint) {
        invoke(removeMethod, point)
    }

    fun connect(a: VirtualFluidPoint, b: VirtualFluidPoint) {
        invoke(connectMethod, a, b)
    }

    fun disconnect(a: VirtualFluidPoint, b: VirtualFluidPoint) {
        invoke(disconnectMethod, a, b)
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllConnected(point: VirtualFluidPoint): Set<VirtualFluidPoint> {
        val result = invoke(getAllConnectedMethod, point) ?: return emptySet()
        return result as? Set<VirtualFluidPoint> ?: emptySet()
    }

    fun setFluidPerSecond(segment: UUID, rate: Double) {
        invoke(setFluidPerSecondMethod, segment, rate)
    }

    /** 所有调用都吞掉异常 —— 流体坏掉不应该拖垮整个插件。 */
    private fun invoke(method: Method?, vararg args: Any?): Any? {
        if (!available || method == null) return null
        return runCatching { method.invoke(null, *args) }.getOrNull()
    }
}
