package com.ueab.sac.service.parse;

import java.io.InputStream;

/**
 * Extracts raw text from uploaded documents (AI Inbox file capture).
 * Implementations: Word, PowerPoint, Excel, PDF, plain text.
 */
public interface FileTextExtractor {

    /** Whether this extractor can handle the given filename/content type. */
    boolean supports(String filename);

    /** Extract all readable text from the stream. */
    String extract(InputStream in) throws Exception;
}
