package app.carecontinuo.care;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DocumentTextTest {
    @Test
    void retainsLineNumbersInText() {
        var source = DocumentText.extract("case.txt", "合成出院记录\n\n预约复诊".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, source.segments().getLast().lineNumber());
    }

    @Test
    void extractsPdfTextWithPageLocation() throws Exception {
        var parsed = DocumentText.extract("case.pdf", pdf(true));
        assertEquals(1, parsed.segments().getFirst().pageNumber());
        assertEquals("Synthetic follow-up appointment", parsed.segments().getFirst().excerpt());
    }

    @Test
    void retainsSourcePagesAndStreamOrderForSyntheticColumnsAndTable() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var columns = new PDPage();
            var table = new PDPage();
            document.addPage(columns);
            document.addPage(table);
            try (var content = new PDPageContentStream(document, columns)) {
                line(content, "Left heading", 72, 720);
                line(content, "Left detail", 72, 700);
                line(content, "Right heading", 330, 720);
                line(content, "Right detail", 330, 700);
            }
            try (var content = new PDPageContentStream(document, table)) {
                line(content, "First column", 72, 720);
                line(content, "Second column", 330, 720);
                line(content, "First value", 72, 700);
                line(content, "Second value", 330, 700);
            }
            document.save(output);
            var segments = DocumentText.extract("layout.pdf", output.toByteArray()).segments();
            var firstPage = segments.stream().filter(line -> line.pageNumber() == 1)
                    .map(DocumentText.Segment::excerpt).toList();
            var secondPage = segments.stream().filter(line -> line.pageNumber() == 2)
                    .map(DocumentText.Segment::excerpt).toList();
            assertInOrder(String.join("\n", firstPage), "Left heading", "Left detail", "Right heading", "Right detail");
            assertInOrder(String.join("\n", secondPage), "First column", "Second column", "First value", "Second value");
        }
    }

    private static void assertInOrder(String extracted, String... expected) {
        int offset = 0;
        for (String part : expected) {
            int match = extracted.indexOf(part, offset);
            assertTrue(match >= 0, () -> "Missing or out of order: " + part + " in " + extracted);
            offset = match + part.length();
        }
    }

    private static void line(PDPageContentStream content, String text, float x, float y) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    @Test
    void rejectsPdfWithoutExtractableText() throws Exception {
        byte[] input = pdf(false);
        var failure = assertThrows(ResponseStatusException.class, () -> DocumentText.extract("scan.pdf", input));
        assertEquals(400, failure.getStatusCode().value());
    }

    private static byte[] pdf(boolean withText) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (withText) {
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(100, 700);
                    content.showText("Synthetic follow-up appointment");
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
