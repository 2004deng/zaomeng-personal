package top.wkbin.zaomeng.feature.importbook

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportDocumentLoaderEpubTest {
    @Test
    fun `epub text follows spine order and skips navigation and non-linear documents`() {
        val epub = epub(
            // Archive order deliberately differs from the package document's spine order.
            entries = listOf(
                "OEBPS/chapter-1.xhtml" to xhtml("第一章"),
                "OEBPS/chapter-2.xhtml" to xhtml("第二章"),
                "OEBPS/nav.xhtml" to xhtml("目录"),
                "OEBPS/appendix.xhtml" to xhtml("附录"),
            ),
            manifest = listOf(
                Triple("c1", "chapter-1.xhtml", "application/xhtml+xml"),
                Triple("c2", "chapter-2.xhtml", "application/xhtml+xml"),
                Triple("nav", "nav.xhtml", "application/xhtml+xml"),
                Triple("appendix", "appendix.xhtml", "application/xhtml+xml"),
            ),
            spine = listOf(
                "c2" to null,
                "c1" to null,
                "appendix" to "no",
            ),
            navigationId = "nav",
        )

        val document = ImportDocumentLoader.prepareImportDocument("book.epub", epub, ImportDocumentKind.NovelText)

        assertEquals("EPUB", document.sourceEncoding)
        assertEquals("第二章\n\n第一章", document.bytes.decodeToString())
        assertEquals("book.txt", document.fileName)
    }

    @Test
    fun `epub without container document is rejected`() {
        val epub = zipOf(listOf("OEBPS/chapter.xhtml" to xhtml("正文")))

        val error = assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument("broken.epub", epub, ImportDocumentKind.NovelText)
        }
        assertTrue("container" in error.message.orEmpty())
    }

    @Test
    fun `epub spine paths escaping the archive root are rejected`() {
        val epub = epub(
            entries = listOf("OEBPS/chapter-1.xhtml" to xhtml("第一章")),
            manifest = listOf(Triple("c1", "../../outside.xhtml", "application/xhtml+xml")),
            spine = listOf("c1" to null),
        )

        assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument("escape.epub", epub, ImportDocumentKind.NovelText)
        }
    }

    @Test
    fun `epub spine pointing at a missing document fails instead of importing other html`() {
        val epub = epub(
            entries = listOf("OEBPS/other.xhtml" to xhtml("其他")),
            manifest = listOf(Triple("c1", "missing.xhtml", "application/xhtml+xml")),
            spine = listOf("c1" to null),
        )

        assertFailsWith<IllegalArgumentException> {
            ImportDocumentLoader.prepareImportDocument("missing.epub", epub, ImportDocumentKind.NovelText)
        }
    }

    @Test
    fun `txt import is unaffected`() {
        val document = ImportDocumentLoader.prepareImportDocument(
            "story.txt",
            "第一章\n正文".encodeToByteArray(),
            ImportDocumentKind.NovelText,
        )

        assertEquals("第一章\n正文", document.bytes.decodeToString())
        assertEquals("UTF-8", document.sourceEncoding)
    }

    private fun epub(
        entries: List<Pair<String, String>>,
        manifest: List<Triple<String, String, String>>,
        spine: List<Pair<String, String?>>,
        navigationId: String? = null,
    ): ByteArray {
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val manifestXml = manifest.joinToString("") { (id, href, mediaType) ->
            val properties = if (id == navigationId) """ properties="nav"""" else ""
            """<item id="$id" href="$href" media-type="$mediaType"$properties/>"""
        }
        val spineXml = spine.joinToString("") { (idref, linear) ->
            val linearAttribute = linear?.let { """ linear="$it"""" }.orEmpty()
            """<itemref idref="$idref"$linearAttribute/>"""
        }
        val packageDocument = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <manifest>$manifestXml</manifest>
              <spine>$spineXml</spine>
            </package>
        """.trimIndent()
        return zipOf(
            listOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to packageDocument,
            ) + entries,
        )
    }

    private fun zipOf(entries: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun xhtml(text: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>$text</p></body></html>"""
}
