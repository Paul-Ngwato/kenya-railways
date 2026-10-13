package com.ueab.sac.service.parse;

import java.io.InputStream;
import java.util.List;

import org.springframework.stereotype.Service;

/** Facade that dispatches to the correct FileTextExtractor based on filename. */
@Service
public class ExtractionService {

    private final List<FileTextExtractor> extractors;

    public ExtractionService(List<FileTextExtractor> extractors) {
        this.extractors = extractors;
    }

    public String extract(String filename, InputStream in) throws Exception {
        for (FileTextExtractor ex : extractors) {
            if (ex.supports(filename)) {
                return ex.extract(in);
            }
        }
        throw new IllegalArgumentException(
                "Unsupported file type: " + filename + " — upload .docx, .pptx, .xlsx, .pdf or .txt");
    }
}
