package com.bnaqica.documentreader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Writes the synthetic fixtures in {@code src/test/resources/fixtures/}. Run by hand, from the project root, when they
 * need to change; the tests read the committed files. Every page carries a solid band across its top 10 % in its
 * document's {@link Marker} colour, which {@link PipelineChatModel} reads in place of the text. The text is for the
 * real model in the golden suite. All names, numbers and amounts are made up.
 */
public final class FixtureGenerator {

    /** One colour per synthetic document, far apart so JPEG and resampling can't confuse them. */
    public enum Marker {
        PAY_STUB("Pay stubs", new Color(220, 30, 30)),
        W2("W-2 forms", new Color(30, 160, 60)),
        BANK_STATEMENT("Bank statements", new Color(30, 60, 200)),
        LICENSE("Driver's license or government photo ID", new Color(240, 200, 0));

        public final String typeName;
        public final Color color;

        Marker(String typeName, Color color) {
            this.typeName = typeName;
            this.color = color;
        }
    }

    static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private FixtureGenerator() {}

    public static void main(String[] args) throws IOException {
        Files.createDirectories(FIXTURES);
        writePacket(FIXTURES.resolve("packet-paystub-w2-bank.pdf"));
        writeLicense(FIXTURES.resolve("license.jpg"));
    }

    private static void writePacket(Path target) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            page(
                    pdf,
                    Marker.PAY_STUB,
                    List.of(
                            "EARNINGS STATEMENT",
                            "Northwind Logistics LLC, 400 Harbor Way, Springfield",
                            "Employee: Jordan Example     Employee ID: 100245",
                            "Pay period: 2026-10-01 to 2026-10-15     Pay date: 2026-10-17",
                            "Gross pay: 3,250.00     Net pay: 2,412.36",
                            "Page 1 of 2"));
            page(
                    pdf,
                    Marker.PAY_STUB,
                    List.of(
                            "EARNINGS STATEMENT (continued)",
                            "Deductions: Federal tax 412.00, State tax 158.40, Social Security 201.50, Medicare 47.13",
                            "Year to date gross: 61,750.00     Year to date net: 45,834.84",
                            "Page 2 of 2"));
            page(
                    pdf,
                    Marker.W2,
                    List.of(
                            "Form W-2 Wage and Tax Statement 2025",
                            "Employer: Northwind Logistics LLC     EIN: 00-0000000",
                            "Employee: Jordan Example",
                            "Box 1 Wages, tips, other compensation: 78,000.00",
                            "Box 2 Federal income tax withheld: 9,880.00"));
            page(
                    pdf,
                    Marker.BANK_STATEMENT,
                    List.of(
                            "FIRST EXAMPLE BANK - ACCOUNT STATEMENT",
                            "Account holder: Jordan Example     Account number: ****0000",
                            "Statement period: 2026-09-01 to 2026-09-30",
                            "Opening balance: 8,120.55",
                            "Page 1 of 3"));
            page(
                    pdf,
                    Marker.BANK_STATEMENT,
                    List.of(
                            "Transactions",
                            "2026-09-03  Payroll deposit  2,412.36",
                            "2026-09-10  Rent  -1,850.00",
                            "2026-09-17  Payroll deposit  2,412.36",
                            "Page 2 of 3"));
            page(
                    pdf,
                    Marker.BANK_STATEMENT,
                    List.of(
                            "Summary",
                            "Total deposits: 4,824.72",
                            "Total withdrawals: 3,102.18",
                            "Closing balance: 9,843.09",
                            "Page 3 of 3"));
            pdf.save(target.toFile());
        }
    }

    private static void page(PDDocument pdf, Marker marker, List<String> lines) throws IOException {
        PDRectangle size = PDRectangle.LETTER;
        PDPage page = new PDPage(size);
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            float bandHeight = size.getHeight() * 0.10f;
            content.setNonStrokingColor(marker.color);
            content.addRect(0, size.getHeight() - bandHeight, size.getWidth(), bandHeight);
            content.fill();

            content.setNonStrokingColor(Color.BLACK);
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.setLeading(20);
            content.newLineAtOffset(54, size.getHeight() - bandHeight - 40);
            for (String line : lines) {
                content.showText(line);
                content.newLine();
            }
            content.endText();
        }
    }

    private static void writeLicense(Path target) throws IOException {
        int width = 1012;
        int height = 638;
        BufferedImage card = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Marker.LICENSE.color);
            g.fillRect(0, 0, width, height / 10);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.drawString("EXAMPLE STATE DRIVER LICENSE", 40, 130);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
            String[] lines = {
                "DL No: X0000000", "Name: JORDAN EXAMPLE", "Address: 12 Sample Street, Springfield",
                "DOB: 1990-04-12", "Issued: 2024-05-01     Expires: 2032-04-12", "Class: C"
            };
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], 40, 200 + i * 60);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(card, "jpg", target.toFile());
    }
}
