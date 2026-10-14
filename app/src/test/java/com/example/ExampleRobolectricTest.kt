package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.pdf.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BookShelf", appName)
  }

  @Test
  fun `pdf sha256 duplicate detection works`() {
    val sampleBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".toByteArray()
    val hash1 = PdfTextExtractor.computeSha256(sampleBytes)
    val hash2 = PdfTextExtractor.computeSha256(sampleBytes)
    assertNotNull(hash1)
    assertEquals(hash1, hash2)
    assertEquals(64, hash1.length)
  }

  @Test
  fun `pdf text extraction and classification handles math textbook`() {
    val mathPdf = "%PDF-1.4\n1 0 obj\n/Title (Calculus and Linear Algebra Primer)\nendobj\nstream\n(derivative equation integral calculus) Tj\nendstream".toByteArray()
    val mathCat = com.example.data.model.CategoryEntity(id = "cat_math", name = "Mathematics", colorHex = "#2563EB", iconName = "Calculate")
    val info = PdfTextExtractor.extractInfo(mathPdf, "calculus.pdf", 3, listOf(mathCat))
    assertEquals("cat_math", info.categoryId)
    assertTrue(info.tags.contains("math") || info.tags.contains("calculus"))
  }
}
