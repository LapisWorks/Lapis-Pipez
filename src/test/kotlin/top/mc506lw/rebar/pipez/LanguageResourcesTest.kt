package top.mc506lw.rebar.pipez

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.yaml.snakeyaml.Yaml

/**
 * 语言文件校验：中英两份必须"键完全一致"，否则玩家切换语言时会看到原始 key。
 * 不需要跑服务器，`./gradlew build` 就会带上。
 */
class LanguageResourcesTest {

    @Test
    fun `language files are valid and expose identical keys`() {
        val chinese = loadLanguage("lang/zh.yml")
        val english = loadLanguage("lang/en.yml")

        assertEquals(flattenKeys(chinese), flattenKeys(english))
    }

    private fun loadLanguage(path: String): Map<String, Any?> {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path), "Missing resource: $path")
        return stream.use { Yaml().load(it) }
    }

    private fun flattenKeys(source: Map<String, Any?>, prefix: String = ""): Set<String> = buildSet {
        for ((key, value) in source) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            add(path)
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                addAll(flattenKeys(value as Map<String, Any?>, path))
            }
        }
    }
}
