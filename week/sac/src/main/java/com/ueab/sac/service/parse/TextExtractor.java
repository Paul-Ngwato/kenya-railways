package com.ueab.sac.service.parse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import org.springframework.stereotype.Component;

/** Extracts text from plain .txt/.md files. */
@Component
public class TextExtractor implements FileTextExtractor {

    @Override
    public boolean supports(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md");
    }

    @Override
    public String extract(InputStream in) throws Exception {
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8)) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }
}
