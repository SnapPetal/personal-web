package biz.thonbecker.personal.budget.platform.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class BudgetDocumentExtractorTest {

    private final BudgetDocumentExtractor extractor = new BudgetDocumentExtractor();

    @Test
    void extractsUtf8CsvAndSanitizesTheFilename() {
        final var file = new MockMultipartFile(
                "files",
                "../monthly-budget.csv",
                "text/csv",
                "category,amount\nGroceries,450".getBytes(StandardCharsets.UTF_8));

        final var documents = extractor.extract(java.util.List.of(file));

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().filename()).isEqualTo("monthly-budget.csv");
        assertThat(documents.getFirst().text()).contains("Groceries,450");
    }

    @Test
    void extractsTextFromPdf() throws Exception {
        final var pdf = new ByteArrayOutputStream();
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage());
            try (final var content = new PDPageContentStream(document, document.getPage(0))) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Monthly budget: groceries $450");
                content.endText();
            }
            document.save(pdf);
        }

        final var file = new MockMultipartFile("files", "budget.pdf", "application/pdf", pdf.toByteArray());

        assertThat(extractor.extract(java.util.List.of(file)).getFirst().text()).contains("groceries $450");
    }

    @Test
    void rejectsUnsupportedFilesAndPdfContentWithTheWrongSignature() {
        assertThatThrownBy(() -> extractor.extract(java.util.List.of(new MockMultipartFile(
                        "files",
                        "budget.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "data".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(BudgetDocumentException.class)
                .hasMessageContaining("Only PDF, CSV, and TXT");

        assertThatThrownBy(() -> extractor.extract(java.util.List.of(
                        new MockMultipartFile("files", "budget.pdf", "application/pdf", "not a pdf".getBytes()))))
                .isInstanceOf(BudgetDocumentException.class)
                .hasMessageContaining("valid PDF data");
    }

    @Test
    void rejectsTooManyFilesAndInvalidUtf8() {
        final var textFile = new MockMultipartFile("files", "budget.txt", "text/plain", "budget".getBytes());
        assertThatThrownBy(() -> extractor.extract(java.util.List.of(textFile, textFile, textFile, textFile)))
                .isInstanceOf(BudgetDocumentException.class)
                .hasMessageContaining("no more than 3");

        assertThatThrownBy(() -> extractor.extract(java.util.List.of(
                        new MockMultipartFile("files", "budget.txt", "text/plain", new byte[] {(byte) 0xC3, 0x28}))))
                .isInstanceOf(BudgetDocumentException.class)
                .hasMessageContaining("UTF-8");
    }
}
