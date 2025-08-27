package com.christopherdro.htmltopdf;

import android.os.Environment;
import android.print.PdfConverter;
import android.print.PrintAttributes;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Semaphore;

public class RNHTMLtoPDFModule extends ReactContextBaseJavaModule {

    private static final String HTML = "html";
    private static final String FILE_NAME = "fileName";
    private static final String FILE_NAMES = "fileNames";
    private static final String DIRECTORY = "directory";
    private static final String WATERMARK = "watermark";
    private static final String BASE_64 = "base64";
    private static final String HEIGHT = "height";
    private static final String WIDTH = "width";

    private static final String PDF_EXTENSION = ".pdf";
    private static final String PDF_PREFIX = "PDF_";

    private final ReactApplicationContext mReactContext;

    final Semaphore mutex = new Semaphore (0);

    public RNHTMLtoPDFModule (ReactApplicationContext reactContext) {
        super (reactContext);
        mReactContext = reactContext;
    }

    @Override
    public String getName () {
        return "RNHTMLtoPDF";
    }

    @ReactMethod
    public void convert (final ReadableMap options, final Promise promise) {
        try {
            File destinationFile;
            String htmlString = options.hasKey (HTML) ? options.getString (HTML) : null;
            if (htmlString == null) {
                promise.reject (new Exception ("RNHTMLtoPDF error: Invalid htmlString parameter."));
                return;
            }

            String fileName;
            if (options.hasKey (FILE_NAME)) {
                fileName = options.getString (FILE_NAME);
                if (!isFileNameValid (fileName)) {
                    promise.reject (new Exception ("RNHTMLtoPDF error: Invalid fileName parameter."));
                    return;
                }
            } else {
                fileName = PDF_PREFIX + UUID.randomUUID ().toString ();
            }

            if (options.hasKey (DIRECTORY)) {
                String state = Environment.getExternalStorageState ();
                File path = new File (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DOCUMENTS), options.getString (DIRECTORY));

                if (!path.exists ()) {
                    if (!path.mkdirs ()) {
                        promise.reject (new Exception ("RNHTMLtoPDF error: Could not create folder structure."));
                        return;
                    }
                }
                destinationFile = new File (path, fileName + PDF_EXTENSION);
            } else {
                destinationFile = getTempFile (fileName);
            }

            PrintAttributes pagesize = null;
            if (options.hasKey (HEIGHT) && options.hasKey (WIDTH)) {
                pagesize = new PrintAttributes.Builder ()
                        .setMediaSize (new PrintAttributes.MediaSize ("custom", "CUSTOM",
                                (int) (options.getInt (WIDTH) * 1000 / 72.0),
                                (int) (options.getInt (HEIGHT) * 1000 / 72.0))
                        )
                        .setResolution (new PrintAttributes.Resolution ("RESOLUTION_ID", "RESOLUTION_ID", 600, 600))
                        .setMinMargins (PrintAttributes.Margins.NO_MARGINS)
                        .build ();
            }

            convertToPDF (htmlString,
                    destinationFile,
                    options.hasKey (BASE_64) && options.getBoolean (BASE_64),
                    Arguments.createMap (),
                    promise,
                    pagesize,
                    mutex
            );
            mutex.acquire ();
            PDDocument newPdf = PDDocument.load (destinationFile);
            PDFBoxResourceLoader.init (getReactApplicationContext ());
            boolean watermark = false;
            if (options.hasKey (WATERMARK)) {
                watermark = options.getBoolean (WATERMARK);
            }
            // Use optimized page numbering method
            addPageNumbersOptimized(newPdf, watermark);
            newPdf.save (destinationFile);
            promise.resolve (destinationFile.getAbsolutePath ());


        } catch (Exception e) {
            promise.reject (e);
        }
    }

    @ReactMethod
    public void mergeAndConvert (
            final ReadableMap options,
            final Promise promise
    ) {
        try {
            File destinationFile;
            File tempDestinationFile;
            String fileName;
            // array for storing temp file paths
            ArrayList<String> tempFiles = new ArrayList<String> ();

            if (options.hasKey (FILE_NAME)) {
                fileName = options.getString (FILE_NAME);
                if (!isFileNameValid (fileName)) {
                    promise.reject (new Exception ("RNHTMLtoPDF error: Invalid fileName parameter."));
                    return;
                }
            } else {
                fileName = PDF_PREFIX + UUID.randomUUID ().toString ();
            }

            if (options.hasKey (DIRECTORY)) {
                String state = Environment.getExternalStorageState ();
                File path = new File (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DOCUMENTS), options.getString (DIRECTORY));

                if (!path.exists ()) {
                    if (!path.mkdirs ()) {
                        promise.reject (new Exception ("RNHTMLtoPDF error: Could not create folder structure."));
                        return;
                    }
                }
                destinationFile = new File (path, fileName + PDF_EXTENSION);
            } else {
                destinationFile = getTempFile (fileName);
            }

            ReadableArray htmlString = options.getArray (HTML);
            if (htmlString == null) {
                promise.reject (new Exception ("RNHTMLtoPDF error: Invalid html parameter."));
            }


            PrintAttributes pagesize = null;
            if (options.hasKey (HEIGHT) && options.hasKey (WIDTH)) {
                pagesize = new PrintAttributes.Builder ()
                        .setMediaSize (new PrintAttributes.MediaSize ("custom", "CUSTOM",
                                (int) (options.getInt (WIDTH) * 1000 / 72.0),
                                (int) (options.getInt (HEIGHT) * 1000 / 72.0))
                        )
                        .setResolution (new PrintAttributes.Resolution ("RESOLUTION_ID", "RESOLUTION_ID", 600, 600))
                        .setMinMargins (PrintAttributes.Margins.NO_MARGINS)
                        .build ();
            }

            for (
                    int i = 0;
                    i < htmlString.size ();
                    i++
            ) {
                Log.d ("RNHTMLtoPDF", "convertAndMerge: " + i);
                tempDestinationFile = getTempFile (fileName + "_" + i);
                tempFiles.add (tempDestinationFile.getAbsolutePath ());
                try {
                    convertToPDF (htmlString.getString (i),
                            tempDestinationFile,
                            options.hasKey (BASE_64) && options.getBoolean (BASE_64),
                            Arguments.createMap (),
                            promise,
                            pagesize,
                            mutex
                    );
                    mutex.acquire ();
                } catch (Exception e) {
                    promise.reject (e);
                    Log.d ("RNHTMLtoPDF", "convertAndMerge error: " + e.getMessage ());
                }

            }

            PDFMergerUtility pdfMerger = new PDFMergerUtility ();
            for (String file : tempFiles) {
                pdfMerger.addSource (new File (file));
            }

            pdfMerger.setDestinationFileName (destinationFile.getAbsolutePath ());
            pdfMerger.setDocumentMergeMode (PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE);
            pdfMerger.mergeDocuments (MemoryUsageSetting.setupTempFileOnly ());

            // Delete temp files
            for (String file : tempFiles) {
                File tempFile = new File (file);
                tempFile.delete ();
            }
            PDDocument newPdf = PDDocument.load (destinationFile);
            PDFBoxResourceLoader.init (getReactApplicationContext ());
            boolean watermark = false;
            if (options.hasKey (WATERMARK)) {
                watermark = options.getBoolean (WATERMARK);
            }
            // Use optimized page numbering method
            addPageNumbersOptimized(newPdf, watermark);
            newPdf.save (destinationFile);
            promise.resolve (destinationFile.getAbsolutePath ());

        } catch (Exception e) {
            promise.reject (e);
        }
    }


    private void convertToPDF (String htmlString, File file, boolean shouldEncode, WritableMap resultMap, Promise promise,
                               PrintAttributes printAttributes,
                               Semaphore mutex
    ) throws Exception {
        PdfConverter pdfConverter = PdfConverter.getInstance ();
        if (printAttributes != null) pdfConverter.setPdfPrintAttrs (printAttributes);
        pdfConverter.convert (mReactContext, htmlString, file, shouldEncode, resultMap, promise, mutex);
    }

    private File getTempFile (String fileName) throws IOException {
        File outputDir = getReactApplicationContext ().getCacheDir ();
        return new File(outputDir, fileName + PDF_EXTENSION);
    }

    private boolean isFileNameValid (String fileName) throws Exception {
        return new File (fileName).getCanonicalFile ().getName ().equals (fileName);
    }

    @ReactMethod
    public void extractAnchorPages(final String filePath, final ReadableArray anchors, final Promise promise) {
        try {
            if (filePath == null) {
                promise.reject(new Exception("RNHTMLtoPDF error: Invalid filePath parameter."));
                return;
            }
            PDFBoxResourceLoader.init (getReactApplicationContext ());
            PDDocument document = PDDocument.load(new File(filePath));
            com.tom_roush.pdfbox.text.PDFTextStripper stripper = new com.tom_roush.pdfbox.text.PDFTextStripper();
            int pageCount = document.getNumberOfPages();

            // Build a quick lookup for anchors
            java.util.HashMap<String, Integer> result = new java.util.HashMap<>();
            java.util.HashSet<String> remaining = new java.util.HashSet<>();
            for (int i = 0; i < anchors.size(); i++) {
                String a = anchors.getString(i);
                remaining.add(a);
            }

            for (int page = 1; page <= pageCount && !remaining.isEmpty(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text == null) text = "";
                for (String a : new java.util.ArrayList<>(remaining)) {
                    String needle = "__TOC_ANCHOR__:" + a;
                    if (text.contains(needle)) {
                        result.put(a, page);
                        remaining.remove(a);
                    }
                }
            }
            document.close();

            WritableMap map = Arguments.createMap();
            for (java.util.Map.Entry<String, Integer> e : result.entrySet()) {
                map.putInt(e.getKey(), e.getValue());
            }
            promise.resolve(map);
        } catch (Exception e) {
            promise.reject(e);
        }
    }

    /**
     * Optimized method to add page numbers and watermarks to PDF pages
     * Reduces performance overhead by pre-calculating values and minimizing repetitive operations
     */
    private void addPageNumbersOptimized(PDDocument document, boolean watermark) throws IOException {
        if (document == null || document.getNumberOfPages() == 0) {
            return;
        }

        // Pre-calculate values outside the loop for better performance
        PDFont pdfFont = PDType1Font.HELVETICA;
        int fontSize = 15;
        String watermarkText = "Powered by Waveform - Upgrade to remove";
        float titleWidth = watermark ? pdfFont.getStringWidth(watermarkText) / 1000 * fontSize : 0;
        int totalPages = document.getNumberOfPages();
        String totalPagesStr = " of " + totalPages;

        // Pre-allocate StringBuilder for string concatenation efficiency
        StringBuilder pageNumberBuilder = new StringBuilder();

        // Loop through all pages with optimizations
        for (int i = 0; i < totalPages; i++) {
            PDPage page = document.getPage(i);

            // Use try-with-resources for automatic resource management
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {

                // Set font and color once per page (unavoidable due to PDFBox API)
                contentStream.setFont(pdfFont, fontSize);
                contentStream.setNonStrokingColor(0f, 0.4f, 0.604f);

                // Optimize page number string building
                pageNumberBuilder.setLength(0); // Clear previous content
                pageNumberBuilder.append("Page ").append(i + 1).append(totalPagesStr);

                // Add page number
                contentStream.beginText();
                contentStream.newLineAtOffset(25, 15);
                contentStream.showText(pageNumberBuilder.toString());
                contentStream.endText();

                // Add watermark if enabled
                if (watermark) {
                    float watermarkX = page.getMediaBox().getWidth() - titleWidth - 15;
                    contentStream.beginText();
                    contentStream.newLineAtOffset(watermarkX, 15);
                    contentStream.showText(watermarkText);
                    contentStream.endText();
                }
            }
        }
    }
}



