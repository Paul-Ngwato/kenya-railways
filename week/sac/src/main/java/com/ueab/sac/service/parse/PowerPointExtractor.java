package com.ueab.sac.service.parse;

import java.io.InputStream;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

/** Extracts text from PowerPoint .pptx files slide by slide. */
@Component
public class PowerPointExtractor implements FileTextExtractor {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pptx");
    }

    @Override
    public String extract(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(in)) {
            int i = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("--- Slide ").append(i++).append(" ---\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t.strip()).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
