package de.lwp2070809.speculonic.ui.screens.library

import android.icu.text.AlphabeticIndex
import de.lwp2070809.speculonic.network.model.Artist
import java.text.Collator
import java.util.Locale

internal fun interface ArtistBucketResolver {
    fun resolveBucket(name: String): String
}

internal object ArtistIndexHelper {
    // 复用 Locale.CHINA 规则以实现中文按拼音排序，PRIMARY 强度忽略声调差异。
    private fun createPinyinCollator(): Collator =
        Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }

    // AlphabeticIndex 在 Android framework 上基于 ICU 提供中文拼音首字母与英文分区；
    // Android JVM 本地单测 stub 无法执行 android.icu，故允许在测试中注入 bucketResolver。
    fun createAndroidBucketResolver(): ArtistBucketResolver {
        val index = AlphabeticIndex<Nothing>(Locale.SIMPLIFIED_CHINESE)
            .addLabels(Locale.ENGLISH)
            .buildImmutableIndex()
        return ArtistBucketResolver { name ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                "#"
            } else {
                val label = index.getBucket(index.getBucketIndex(trimmed)).label
                normalizeBucketLabel(label)
            }
        }
    }

    internal fun normalizeBucketLabel(label: String?): String {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.length == 1) {
            val upper = trimmed[0].uppercaseChar()
            if (upper in 'A'..'Z') return upper.toString()
        }
        return "#"
    }

    fun groupAndSort(
        artists: List<Artist>,
        bucketResolver: ArtistBucketResolver = createAndroidBucketResolver()
    ): Map<String, List<Artist>> {
        val collator = createPinyinCollator()
        val comparator = Comparator<Artist> { a, b ->
            val cmp = collator.compare(a.name.trim(), b.name.trim())
            if (cmp != 0) cmp
            else {
                val ci = a.name.compareTo(b.name, ignoreCase = true)
                if (ci != 0) ci else a.id.compareTo(b.id)
            }
        }
        val grouped = artists.groupBy { artist ->
            bucketResolver.resolveBucket(artist.name)
        }
        val ordered = LinkedHashMap<String, List<Artist>>(grouped.size)
        for (ch in 'A'..'Z') {
            val key = ch.toString()
            grouped[key]?.let { list ->
                ordered[key] = list.sortedWith(comparator)
            }
        }
        grouped["#"]?.let { list ->
            ordered["#"] = list.sortedWith(comparator)
        }
        grouped.keys
            .filter { it !in ordered.keys }
            .sorted()
            .forEach { extraKey ->
                grouped[extraKey]?.let { list ->
                    ordered[extraKey] = list.sortedWith(comparator)
                }
            }
        return ordered
    }
}
