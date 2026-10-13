package com.ueab.sac.service.parse;

import java.io.InputStream;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Extracts text from Excel .xlsx files (PhpSpreadsheet equivalent). */
@Component
public class ExcelExtractor implements FileTextExtractor {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".xlsx");
    }

    @Override
    public String extract(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            DataFormatter fmt = new DataFormatter();
            for (Sheet sheet : wb) {
                sb.append("## Sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (var cell : row) {
                        String v = fmt.formatCellValue(cell);
                        if (!v.isBlank()) {
                            if (line.length() > 0) line.append(" | ");
                            line.append(v);
                        }
                    }
                    if (line.length() > 0) sb.append(line).append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
