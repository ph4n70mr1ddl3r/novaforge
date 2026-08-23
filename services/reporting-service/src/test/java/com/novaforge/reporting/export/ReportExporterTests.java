package com.novaforge.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novaforge.common.error.PlatformException;
import com.novaforge.metadata.DefinitionParser;
import com.novaforge.metadata.ReportDefinition;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The export contract (PHASE-5 §6, §10 item 3): RFC 4180 quoting, money columns as
 * locale-formatted currency strings, the sync cap rejecting with async-export
 * guidance, and CSV/XLSX parity — the same table (header, body, totals row) renders
 * through both formats, pinned by parsing the XLSX back and comparing cell for cell.
 */
class ReportExporterTests {

    private static final ReportDefinition REPORT = DefinitionParser.parse("""
            { "id": "arAging", "entity": "Invoice",
              "groupBy": [ { "field": "customer" } ],
              "aggregates": [ { "op": "sum", "field": "amountOutstanding" } ] }
            """, ReportDefinition.class);

    private final ReportExporter exporter = new ReportExporter("USD");

    /** A two-row run with a money column and a totals row, US locale for determinism. */
    private static Map<String, Object> run() {
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("customer", "acme");
        row1.put("sum_amount_outstanding", new BigDecimal("300.50"));
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("customer", "globex");
        row2.put("sum_amount_outstanding", new BigDecimal("50.25"));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("sum_amount_outstanding", new BigDecimal("350.75"));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("columns", List.of("customer", "sum_amount_outstanding"));
        run.put("rows", List.of(row1, row2));
        run.put("totals", totals);
        return run;
    }

    @Test
    @DisplayName("RFC 4180: quotes, commas, and newlines escape by doubling")
    void csvQuoting() {
        assertThat(ReportExporter.csvCell("plain")).isEqualTo("plain");
        assertThat(ReportExporter.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ReportExporter.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ReportExporter.csvCell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(ReportExporter.csvCell(null)).isEmpty();
    }

    @Test
    @DisplayName("money columns render with the currency symbol; others stay plain decimals")
    void moneyFormatting() {
        Set<String> money = Set.of("sum_amount_outstanding");
        byte[] csv = exporter.csv(run(), money, Locale.US);
        String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("$300.50").contains("$50.25").contains("$350.75");
        assertThat(text).contains("acme");
        // a non-money decimal keeps its plain form — never a currency symbol
        Map<String, Object> plain = run();
        plain.put("columns", List.of("customer"));
        assertThat(new String(exporter.csv(plain, Set.of(), Locale.US),
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("$");
    }

    @Test
    @DisplayName("CSV/XLSX parity: the same table cell for cell, header through totals")
    void csvXlsxParity() throws Exception {
        Set<String> money = Set.of("sum_amount_outstanding");
        byte[] csv = exporter.csv(run(), money, Locale.US);
        byte[] xlsx = exporter.xlsx(run(), REPORT, money, Locale.US);
        List<List<String>> csvTable = parseCsv(new String(csv, java.nio.charset.StandardCharsets.UTF_8));
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("arAging");
            assertThat(sheet.getLastRowNum() + 1).isEqualTo(csvTable.size());
            for (int r = 0; r < csvTable.size(); r++) {
                Row row = sheet.getRow(r);
                for (int c = 0; c < csvTable.get(r).size(); c++) {
                    assertThat(row.getCell(c).getStringCellValue())
                            .isEqualTo(csvTable.get(r).get(c));
                }
            }
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("TOTAL");
        }
    }

    @Test
    @DisplayName("the sync cap rejects with async-export guidance (§6)")
    void capRejectsWithGuidance() {
        exporter.requireWithinCap(10_000, 10_000);   // the boundary itself passes
        assertThatThrownBy(() -> exporter.requireWithinCap(10_001, 10_000))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("synchronous exports are capped at 10000 rows")
                .hasMessageContaining("async export");
    }

    /** Minimal RFC 4180 reader for the parity pin — the corpus is quote-safe here. */
    private static List<List<String>> parseCsv(String text) {
        return text.lines().map(line ->
                java.util.Arrays.stream(line.split(",", -1))
                        .map(cell -> cell.startsWith("\"") && cell.endsWith("\"")
                                ? cell.substring(1, cell.length() - 1).replace("\"\"", "\"")
                                : cell)
                        .toList()).toList();
    }
}
