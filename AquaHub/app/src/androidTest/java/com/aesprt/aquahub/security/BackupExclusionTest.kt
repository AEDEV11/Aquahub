package com.aesprt.aquahub.security

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class BackupExclusionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appBackupIsDisabledAndLegacyRulesExcludeSensitiveStorageDomains() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val excludes = parseExcludes(R.xml.backup_rules)
        assertTrue(excludes.containsAll(ALL_STORAGE_DOMAINS.map { it to "." }))
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeAccountBoundStorage() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        val excludes = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup", "device-transfer" -> section = parser.name
                        "exclude" -> section?.let { name ->
                            val domain = parser.getAttributeValue(null, "domain")
                            val path = parser.getAttributeValue(null, "path")
                            if (domain != null && path != null) {
                                excludes.getOrPut(name) { mutableSetOf() }.add(domain to path)
                            }
                        }
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG &&
                    (parser.name == "cloud-backup" || parser.name == "device-transfer")
                ) {
                    section = null
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        ALL_STORAGE_DOMAINS.forEach { domain ->
            assertTrue("cloud-backup must exclude $domain", (domain to ".") in excludes["cloud-backup"].orEmpty())
            assertTrue("device-transfer must exclude $domain", (domain to ".") in excludes["device-transfer"].orEmpty())
        }
    }

    private fun parseExcludes(resourceId: Int): Set<Pair<String, String>> {
        val parser = context.resources.getXml(resourceId)
        val excludes = mutableSetOf<Pair<String, String>>()
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    val domain = parser.getAttributeValue(null, "domain")
                    val path = parser.getAttributeValue(null, "path")
                    if (domain != null && path != null) excludes += domain to path
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return excludes
    }

    private companion object {
        val ALL_STORAGE_DOMAINS = setOf("root", "file", "database", "sharedpref", "external")
    }
}
