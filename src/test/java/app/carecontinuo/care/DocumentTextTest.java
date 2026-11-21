package app.carecontinuo.care;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
