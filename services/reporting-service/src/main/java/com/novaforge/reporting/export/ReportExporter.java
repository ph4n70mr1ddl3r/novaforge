package com.novaforge.reporting.export;

import com.novaforge.common.error.PlatformErrorCode;
import com.novaforge.common.error.PlatformException;
import com.novaforge.common.error.ProblemErrors;
import com.novaforge.metadata.ReportDefinition;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Export rendering (§6): CSV and XLSX, synchronous streams with the same
 * authorization as a run. The sync cap — 10,000 rows — returns a problem+json
 * pointing at the async export job that activates with the File Service in Phase 6
 * (the handoff is designed now, wired then). Money columns render as decimal strings
 * with the currency symbol (v1's single configured currency; multi-currency arrives
 * with the Phase 7 dogfood scope), bucket labels ride verbatim, and the totals row
 * closes the file. CSV/XLSX parity is pinned by test (§10 item 3).
 */
@Component
public class ReportExporter {

    private final String currency;

    public ReportExporter(@Value("${novaforge.reporting.currency:USD}") String currency) {
        this.currency = currency;
    }

    /**
     * RFC 4180 quoting plus formula neutralization: a leading {@code =}, {@code +},
     * {@code -}, {@code @} (or tab/CR) makes spreadsheet apps treat the cell as a
     * formula on open — group-by labels are raw record data, so a crafted label like
     * {@code =WEBSERVICE(…)} must never ride verbatim into a CSV someone opens.
     */
    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && ("=+-@".indexOf(text.charAt(0)) >= 0
                || text.charAt(0) == '\t' || text.charAt(0) == '\r')) {
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n")
                || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** The §6 cap gate — larger exports point at the Phase 6 async job. */
    public void requireWithinCap(long rowCount, long cap) {
        if (rowCount > cap) {
            throw new PlatformException(PlatformErrorCode.VALIDATION_FAILED,
                    "synchronous exports are capped at " + cap + " rows — this report "
                            + "carries " + rowCount + "; the async export job (File Service, "
                            + "PHASE-6 §7) serves it when that lands",
                    ProblemErrors.of(new ProblemErrors.FieldError("rows",
                            "sync export cap is " + cap + " rows; async export activates "
                                    + "with Phase 6", rowCount)));
        }
    }

    /** Renders a run result ({columns, rows, totals}) as CSV bytes. */
    public byte[] csv(Map<String, Object> run, Set<String> moneyColumns, Locale locale) {
        List<String> columns = columns(run);
        List<List<String>> table = table(run, columns, moneyColumns, locale);
        StringBuilder out = new StringBuilder();
        appendCsvRow(out, columns);
        table.forEach(row -> appendCsvRow(out, row));
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Renders the same run as XLSX — numeric cells stay numeric, totals row closes. */
    public byte[] xlsx(Map<String, Object> run, ReportDefinition report,
                       Set<String> moneyColumns, Locale locale) {
        List<String> columns = columns(run);
        List<List<String>> table = table(run, columns, moneyColumns, locale);
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(report));
            CellStyle header = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            header.setFont(font);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(header);
            }
            int at = 1;
            for (List<String> rendered : table) {
                Row sheetRow = sheet.createRow(at++);
                for (int i = 0; i < rendered.size(); i++) {
                    sheetRow.createCell(i).setCellValue(rendered.get(i));
                }
            }
            // sized after the data (autosizing over the header alone was a no-op);
            // POI's autosize walks AWT fonts — a headless server without fontconfig
            // must degrade to a fixed width, never lose the export (review fix)
            for (int i = 0; i < columns.size(); i++) {
                try {
                    sheet.autoSizeColumn(i);
                } catch (RuntimeException e) {
                    sheet.setColumnWidth(i, 18 * 256);
                }
            }
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.INTERNAL,
                    "xlsx render failed: " + e.getMessage(), null, e);
        }
    }

    // --- shared shaping: body rows then the totals row, both string-rendered ---

    /**
     * A sheet name POI accepts: workbook sheet names cap at 31 characters (POI
     * throws otherwise), and report ids carry no length cap — a legal
     * 40-character id must not fail every XLSX export.
     */
    private static String sheetName(ReportDefinition report) {
        String id = report.id() == null ? "report" : report.id();
        return id.length() <= 31 ? id : id.substring(0, 31);
    }

    private List<List<String>> table(Map<String, Object> run, List<String> columns,
                                     Set<String> moneyColumns, Locale locale) {
        List<List<String>> table = new ArrayList<>();
        for (Map<String, Object> row : rows(run)) {
            List<String> cells = new ArrayList<>();
            columns.forEach(column -> cells.add(
                    format(row.get(column), moneyColumns.contains(column), locale)));
            table.add(cells);
        }
        Map<String, Object> totals = totalsOf(run);
        if (!totals.isEmpty()) {
            List<String> totalRow = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i);
                // the label rides the first GROUP column — an aggregate-only report
                // (no group-by) has a value in column 0, and "TOTAL" must not clobber it
                totalRow.add(i == 0 && !totals.containsKey(column) ? "TOTAL"
                        : format(totals.get(column), moneyColumns.contains(column), locale));
            }
            table.add(totalRow);
        }
        return table;
    }

    /**
     * Money columns render as decimal strings with the currency symbol — exact
     * scale preserved ({@code toPlainString}), never a rounded, grouped currency
     * format: §10 item 1 pins decimal-exact totals, and a scale-4 sum like
     * {@code 120.1234} must export whole. Everything else rides its natural
     * string form.
     */
    private String format(Object value, boolean money, Locale locale) {
        if (value == null) {
            return "";
        }
        if (money && value instanceof BigDecimal decimal) {
            return currencySymbol(locale) + decimal.toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    /** v1 pins one configured currency per service (multi-currency is Phase 7 scope). */
    private String currencySymbol(Locale locale) {
        try {
            return Currency.getInstance(currency).getSymbol(locale);
        } catch (IllegalArgumentException e) {
            return currency;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> columns(Map<String, Object> run) {
        return (List<String>) run.getOrDefault("columns", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.getOrDefault("rows", List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totalsOf(Map<String, Object> run) {
        return (Map<String, Object>) run.getOrDefault("totals", Map.of());
    }

    private static void appendCsvRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            out.append(csvCell(cells.get(i)));
            if (i < cells.size() - 1) {
                out.append(',');
            }
        }
        out.append("\r\n");
    }
}
