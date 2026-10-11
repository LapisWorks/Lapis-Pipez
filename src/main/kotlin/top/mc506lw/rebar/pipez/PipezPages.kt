package top.mc506lw.rebar.pipez

import io.github.pylonmc.rebar.content.guide.RebarGuide
import io.github.pylonmc.rebar.guide.pages.base.SimpleStaticGuidePage
import top.mc506lw.rebar.pipez.conduit.PipeType

/**
 * 指南书（游戏内手册）页面。
 *
 * **不注册这个，内容在手册里就看不到。** 新加内容记得同时加进 [pageItems]。
 * 页面标题取 lang 里的 `guide.page.pipez`。
 */
object PipezPages {

    val PAGE = SimpleStaticGuidePage(PipezKeys.key(PipezKeys.NAMESPACE))

    private val pageItems by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            // 每种类型的管道各占一个条目（能量管、流体管、物品管）
            PipeType.entries.forEach { add(PipezBlocks.conduitItemFor(it)) }
            add(PipezItems.WRENCH)
            addAll(PipezItems.UPGRADES.values)
        }
    }

    fun initialise() {
        pageItems.forEach(PAGE::addItem)
        // 入口用能量管的物品（最常用的那种）
        RebarGuide.rootPage.addPage(PipezBlocks.conduitItemFor(PipeType.ENERGY), PAGE)
    }
}
