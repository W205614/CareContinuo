package app.carecontinuo.care;

import app.carecontinuo.common.Problems;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class DocumentText {
    private DocumentText() { }

    static Extracted extract(String filename, byte[] bytes) {
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String extension = name.toLowerCase(Locale.ROOT);
        if (name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw Problems.invalid("附件名称无效。");
        }
        try {
            if (extension.endsWith(".pdf")) return pdf(name, bytes);
            if (extension.endsWith(".txt")) {
                var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                return checked(name, "text/plain", bytes, lines(null, text.replaceFirst("^\\uFEFF", "")));
            }
        } catch (IOException exception) {
            throw Problems.invalid("无法读取附件。请检查文件，或手工补录出院资料。");
        }
        throw Problems.invalid("支持 UTF-8 文本和文本 PDF。扫描件请手工补录。");
    }

    private static Extracted pdf(String name, byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > 50) throw Problems.invalid("PDF 最多支持 50 页。");
            var reader = new PDFTextStripper();
            var result = new ArrayList<Segment>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                reader.setStartPage(page);
                reader.setEndPage(page);
                result.addAll(lines(page, reader.getText(document)));
            }
            return checked(name, "application/pdf", bytes, result);
        }
    }

    private static List<Segment> lines(Integer page, String text) {
        var segments = new ArrayList<Segment>();
        String[] all = text.split("\\R", -1);
        for (int index = 0; index < all.length; index++) {
            String line = all[index].strip();
            if (!line.isEmpty()) segments.add(new Segment(page, index + 1, line));
        }
        return segments;
    }

    private static Extracted checked(String name, String mediaType, byte[] bytes, List<Segment> segments) {
        if (segments.isEmpty()) throw Problems.invalid("未提取到文本。扫描件请手工补录出院资料。");
        if (segments.stream().mapToLong(segment -> segment.excerpt().length()).sum() > 80_000) {
            throw Problems.invalid("附件文本过长，请拆成较小的资料。");
        }
        if (segments.stream().anyMatch(segment -> segment.excerpt().indexOf(0) >= 0)) {
            throw Problems.invalid("附件包含无效的文本字符。");
        }
        return new Extracted(name, mediaType, bytes, segments);
    }

    record Segment(Integer pageNumber, int lineNumber, String excerpt) { }
    record Extracted(String filename, String mediaType, byte[] content, List<Segment> segments) { }
}
