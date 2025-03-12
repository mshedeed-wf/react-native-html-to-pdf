/*
 * Created on 11/15/17.
 * Written by Islam Salah with assistance from members of Blink22.com
 */

package android.print;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.Semaphore;

/**
 * Converts HTML to PDF.
 * <p>
 * Can convert only one task at a time, any requests to do more conversions before
 * ending the current task are ignored.
 */
public class PdfConverter implements Runnable {

    private static final String TAG = "PdfConverter";
    private static PdfConverter sInstance;

    private Context mContext;

    private Semaphore mMutex;
    private String mHtmlString;
    private File mPdfFile;
    private PrintAttributes mPdfPrintAttrs;
    private boolean mIsCurrentlyConverting;
    private WebView mWebView;
    private boolean mShouldEncode;
    private WritableMap mResultMap;
    private Promise mPromise;

    private PdfConverter () {
    }

    public static synchronized PdfConverter getInstance () {
        if (sInstance == null)
            sInstance = new PdfConverter ();

        return sInstance;
    }

    @Override
    public void run () {
        mWebView = new WebView (mContext);
        WebSettings settings = mWebView.getSettings ();
        settings.setDefaultTextEncodingName ("utf-8");
        settings.setAllowFileAccess (true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mWebView.setRendererPriorityPolicy (WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        mWebView.setWebViewClient (new WebViewClient () {
            @Override
            public void onPageFinished (WebView view, String url) {
                super.onPageFinished (view, url);
                if (view.getContentHeight () == 0) {
                    view.reload ();
                    return;
                }
                if (!mIsCurrentlyConverting) {
                    mIsCurrentlyConverting = true;
                    PrintDocumentAdapter printAdapter = mWebView.createPrintDocumentAdapter ();
                    printAdapter.onLayout (null, getPdfPrintAttrs (), null, new PrintDocumentAdapter.LayoutResultCallback () {
                    }, null);
                    printAdapter.onWrite (new PageRange[]{PageRange.ALL_PAGES}, getOutputFileDescriptor (), null, new PrintDocumentAdapter.WriteResultCallback () {
                        @Override
                        public void onWriteFinished (PageRange[] pages) {
                            super.onWriteFinished (pages);
                            destroy ();
                            mMutex.release ();
                        }

                        @Override
                        public void onWriteFailed (CharSequence error) {
                            super.onWriteFailed (error);
                            destroy ();
                            mMutex.release ();
                        }

                        @Override
                        public void onWriteCancelled () {
                            super.onWriteCancelled ();
                            destroy ();
                            mMutex.release ();
                        }
                    });
                }
            }

        });
        mWebView.loadDataWithBaseURL ("file:///", mHtmlString, "text/HTML", "utf-8", null);
    }

    public PrintAttributes getPdfPrintAttrs () {
        return mPdfPrintAttrs != null ? mPdfPrintAttrs : getDefaultPrintAttrs ();
    }

    public void setPdfPrintAttrs (PrintAttributes printAttrs) {
        this.mPdfPrintAttrs = printAttrs;
    }

    public void convert (Context context, String htmlString, File file, boolean shouldEncode, WritableMap resultMap,
                         Promise promise, Semaphore mutex) throws Exception {
        if (context == null)
            throw new Exception ("context can't be null");
        if (htmlString == null)
            throw new Exception ("htmlString can't be null");
        if (file == null)
            throw new Exception ("file can't be null");

        if (mIsCurrentlyConverting)
            return;

        mContext = context;
        mHtmlString = htmlString;
        mPdfFile = file;
        mShouldEncode = shouldEncode;
        mResultMap = resultMap;
        mPromise = promise;
        mMutex = mutex;
        runOnUiThread (this);
    }

    private ParcelFileDescriptor getOutputFileDescriptor () {
        try {
            mPdfFile.createNewFile ();
            return ParcelFileDescriptor.open (mPdfFile, ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (Exception e) {
            Log.d (TAG, "Failed to open ParcelFileDescriptor", e);
        }
        return null;
    }

    private PrintAttributes getDefaultPrintAttrs () {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return null;

        return new PrintAttributes.Builder ()
                .setMediaSize (PrintAttributes.MediaSize.ISO_A3)
                .setResolution (new PrintAttributes.Resolution ("RESOLUTION_ID", "RESOLUTION_ID", 600, 600))
                .setMinMargins (PrintAttributes.Margins.NO_MARGINS)
                .build ();

    }

    private void runOnUiThread (Runnable runnable) {
        Handler handler = new Handler (mContext.getMainLooper ());
        handler.post (runnable);
    }

    private void destroy () {
        mContext = null;
        mHtmlString = null;
        mPdfFile = null;
        mPdfPrintAttrs = null;
        mWebView = null;
        mIsCurrentlyConverting = false;
        mShouldEncode = false;
        mResultMap = null;
        mPromise = null;
    }

    private String encodeFromFile (File file) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile (file, "r");
        byte[] fileBytes = new byte[(int) randomAccessFile.length ()];
        randomAccessFile.readFully (fileBytes);
        return Base64.encodeToString (fileBytes, Base64.DEFAULT);
    }
}
