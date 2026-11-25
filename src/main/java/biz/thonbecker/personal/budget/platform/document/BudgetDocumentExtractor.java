package biz.thonbecker.personal.budget.platform.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class BudgetDocumentExtractor {

    static final int MAX_FILES = 3;
    static final long MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final int MAX_CONTEXT_CHARACTERS = 30_000;
    private static final int MAX_PDF_PAGES = 30;

    public List<BudgetDocument> extract(final List<MultipartFile> files) {
        final var attachments = files == null
                ? List.<MultipartFile>of()
                : files.stream().filter(file -> file != null && !file.isEmpty()).toList();

        if (attachments.size() > MAX_FILES) {
            throw new BudgetDocumentException("Attach no more than " + MAX_FILES + " files.");
        }

        final var documents = new ArrayList<BudgetDocument>();
        var totalCharacters = 0;
        for (final var file : attachments) {
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new BudgetDocumentException("Each attachment must be 5 MB or smaller.");
            }

            final var filename = safeFilename(file.getOriginalFilename());
            final var bytes = readBytes(file);
            final var text = switch (extension(filename)) {
                case "pdf" -> extractPdf(bytes);
                case "csv", "txt" -> extractText(bytes);
                default -> throw new BudgetDocumentException("Only PDF, CSV, and TXT files are supported.");
            };

            if (text.isBlank()) {
                throw new BudgetDocumentException("Could not find readable text in " + filename + ".");
            }

            totalCharacters += text.length();
            if (totalCharacters > MAX_CONTEXT_CHARACTERS) {
                throw new BudgetDocumentException("The combined attachment text must be 30,000 characters or less.");
            }
            documents.add(new BudgetDocument(filename, text));
        }
        return List.copyOf(documents);
    }

    private static byte[] readBytes(final MultipartFile file) {
        try {
            return file.getBytes();
        } catch (final IOException exception) {
            throw new BudgetDocumentException("Unable to read an attachment.", exception);
        }
    }

    private static String extractPdf(final byte[] bytes) {
        if (bytes.length < 5
                || bytes[0] != '%'
                || bytes[1] != 'P'
                || bytes[2] != 'D'
                || bytes[3] != 'F'
                || bytes[4] != '-') {
            throw new BudgetDocumentException("A PDF attachment does not contain valid PDF data.");
        }

        try (final var document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new BudgetDocumentException("PDF attachments must have 30 pages or fewer.");
            }
            return cleanText(new PDFTextStripper().getText(document));
        } catch (final IOException exception) {
            throw new BudgetDocumentException("Unable to read a PDF attachment.", exception);
        }
    }

    private static String extractText(final byte[] bytes) {
        try {
            final var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return cleanText(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (final CharacterCodingException exception) {
            throw new BudgetDocumentException("Text and CSV attachments must use UTF-8 encoding.", exception);
        }
    }

    private static String cleanText(final String text) {
        return text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
    }

    private static String safeFilename(final String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BudgetDocumentException("Each attachment must have a filename.");
        }
        final var basename = filename.replace('\\', '/');
        final var name = basename.substring(basename.lastIndexOf('/') + 1);
        final var safeName = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        return safeName.substring(0, Math.min(safeName.length(), 100));
    }

    private static String extension(final String filename) {
        final var lastDot = filename.lastIndexOf('.');
        return lastDot < 0 ? "" : filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    public record BudgetDocument(String filename, String text) {}
}
