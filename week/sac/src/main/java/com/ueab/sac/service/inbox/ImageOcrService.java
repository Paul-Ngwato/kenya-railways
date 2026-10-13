package com.ueab.sac.service.inbox;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;

/**
 * Offline OCR for images (photos/screenshots of timetables, notices, chat
 * screenshots). Used when no vision LLM is configured, or when the vision call
 * fails. Only runs when an image is actually uploaded, so the native Tesseract
 * library is only touched on demand.
 */
@Service
public class ImageOcrService {

    private static final Logger log = LoggerFactory.getLogger(ImageOcrService.class);

    /** Image extensions handled by the inbox upload path. */
    public static boolean isImageFilename(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    /** Best-effort MIME type for common image extensions. */
    public static String mimeFor(String name) {
        if (name == null) return "image/png";
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    @Value("${app.ocr.enabled:true}")
    private boolean enabled;

    @Value("${app.ocr.tessdata-path:./tessdata}")
    private String tessdataPath;

    private volatile ITesseract tesseract;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs OCR on an image stream. Returns the recognized text, or null when
     * OCR is disabled/unavailable (so callers can show a friendly warning).
     * Catches Throwable on purpose: Tesseract loads native libraries that can
     * throw UnsatisfiedLinkError (an Error, not Exception) on machines missing
     * the native runtime — OCR must never take down an upload.
     */
    public String recognize(InputStream in) {
        if (!enabled) return null;
        try {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            return tesseract().doOCR(img);
        } catch (Throwable t) {
            log.warn("OCR unavailable/failed: {}", t.toString());
            return null;
        }
    }

    private ITesseract tesseract() {
        ITesseract t = tesseract;
        if (t == null) {
            synchronized (this) {
                t = tesseract;
                if (t == null) {
                    Tesseract inst = new Tesseract();
                    inst.setDatapath(resolveTessdataPath());
                    inst.setLanguage("eng");
                    // tolerate minor quality issues in photos/screenshots
                    inst.setVariable("tessedit_do_invert", "0");
                    tesseract = t = inst;
                }
            }
        }
        return t;
    }

    /** Finds eng.traineddata in common locations regardless of the working dir. */
    private String resolveTessdataPath() {
        Path configured = Path.of(tessdataPath == null || tessdataPath.isBlank()
                ? "./tessdata" : tessdataPath);
        if (Files.exists(configured.resolve("eng.traineddata"))) {
            return configured.toString();
        }
        for (Path candidate : new Path[]{
                Path.of("sac", "tessdata"),
                Path.of("..", "tessdata"),
                Path.of("..", "sac", "tessdata")}) {
            if (Files.exists(candidate.resolve("eng.traineddata"))) {
                log.info("Using tessdata at {}", candidate.toAbsolutePath());
                return candidate.toString();
            }
        }
        return configured.toString(); // let Tesseract produce its own error
    }
}
