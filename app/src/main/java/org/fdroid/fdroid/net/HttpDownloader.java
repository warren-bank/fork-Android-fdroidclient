package org.fdroid.fdroid.net;

import android.annotation.TargetApi;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import info.guardianproject.netcipher.NetCipher;
import org.apache.commons.io.FileUtils;
import org.fdroid.fdroid.BuildConfig;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Utils;
import org.spongycastle.util.encoders.Base64;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * Download files over HTTP, with support for proxies, {@code .onion} addresses,
 * HTTP Basic Auth, etc.  This is not a full HTTP client!  This is only using
 * the bits of HTTP that F-Droid needs to operate.  It does not support things
 * like redirects or other HTTP tricks.  This keeps the security model and code
 * a lot simpler.
 */
public class HttpDownloader extends Downloader {
    private static final String TAG = "HttpDownloader";

    private static final String HEADER_FIELD_ETAG = "ETag";

    private final String username;
    private final String password;
    private URL sourceUrl;
    private HttpURLConnection connection;
    private boolean newFileAvailableOnServer;

    HttpDownloader(Uri uri, File destFile)
            throws FileNotFoundException, MalformedURLException {
        this(uri, destFile, null, null);
    }

    /**
     * Create a downloader that can authenticate via HTTP Basic Auth using the supplied
     * {@code username} and {@code password}.
     *
     * @param uri      The file to download
     * @param destFile Where the download is saved
     * @param username Username for HTTP Basic Auth, use {@code null} to ignore
     * @param password Password for HTTP Basic Auth, use {@code null} to ignore
     * @throws FileNotFoundException
     * @throws MalformedURLException
     */
    HttpDownloader(Uri uri, File destFile, String username, String password)
            throws FileNotFoundException, MalformedURLException {
        super(uri, destFile);
        this.sourceUrl = new URL(urlString);
        this.username = username;
        this.password = password;
    }

    @Override
    protected InputStream getDownloadersInputStream() throws IOException {
        setupConnection(false);
        return new BufferedInputStream(connection.getInputStream());
    }

    /**
     * Get a remote file, checking the HTTP response code and the {@code etag}.
     * In order to prevent the {@code etag} from being used as a form of tracking
     * cookie, this code never sends the {@code etag} to the server.  Instead, it
     * uses a {@code HEAD} request to get the {@code etag} from the server, then
     * only issues a {@code GET} if the {@code etag} has changed.
     *
     * @see <a href="http://lucb1e.com/rp/cookielesscookies">Cookieless cookies</a>
     */
    @Override
    public void download() throws ConnectException, IOException, InterruptedException {
        // get the file size from the server
        HttpURLConnection tmpConn = getConnection();
        tmpConn.setRequestMethod("HEAD");
        String etag = tmpConn.getHeaderField(HEADER_FIELD_ETAG);

        int contentLength = -1;
        int statusCode = tmpConn.getResponseCode();
        tmpConn.disconnect();
        newFileAvailableOnServer = false;
        switch (statusCode) {
            case 200:
                contentLength = tmpConn.getContentLength();
                if (!TextUtils.isEmpty(etag) && etag.equals(cacheTag)) {
                    Utils.debugLog(TAG, urlString + " is cached, not downloading");
                    return;
                }
                newFileAvailableOnServer = true;
                break;
            case 404:
                notFound = true;
                return;
            default:
                Utils.debugLog(TAG, "HEAD check of " + urlString + " returned " + statusCode + ": "
                        + tmpConn.getResponseMessage());
        }

        boolean resumable = false;
        long fileLength = outputFile.length();
        if (fileLength > contentLength) {
            FileUtils.deleteQuietly(outputFile);
        } else if (fileLength == contentLength && outputFile.isFile()) {
            return; // already have it!
        } else if (fileLength > 0) {
            resumable = true;
        }
        setupConnection(resumable);
        Utils.debugLog(TAG, "downloading " + urlString + " (is resumable: " + resumable + ")");
        downloadFromStream(8192, resumable);
        cacheTag = connection.getHeaderField(HEADER_FIELD_ETAG);
    }

    public static boolean isSwapUrl(Uri uri) {
        return isSwapUrl(uri.getHost(), uri.getPort());
    }

    public static boolean isSwapUrl(URL url) {
        return isSwapUrl(url.getHost(), url.getPort());
    }

    public static boolean isSwapUrl(String host, int port) {
        return port > 1023 // only root can use <= 1023, so never a swap repo
                && host.matches("[0-9.]+") // host must be an IP address
                && FDroidApp.subnetInfo.isInRange(host); // on the same subnet as we are
    }

    private HttpURLConnection getConnection() throws SocketTimeoutException, IOException {
        HttpURLConnection connection = null;
        URL url = sourceUrl;
        int redirectCounter = 0;
        do {
            if (connection != null) {
                // throws MalformedURLException, a type of IOException
                url = new URL(connection.getHeaderField("Location"));
            }

            if (url == null) {
                break;
            } else if (isSwapUrl(url)) {
                // swap never works with a proxy, its unrouted IP on the same subnet
                connection = (HttpURLConnection) url.openConnection();
                // avoid keep-alive
                connection.setRequestProperty("Connection", "Close");
            } else {
                connection = NetCipher.getHttpURLConnection(url);
            }

            connection.setRequestProperty("User-Agent", "F-Droid " + BuildConfig.VERSION_NAME);
            connection.setConnectTimeout(getTimeout());
            connection.setReadTimeout(getTimeout());

            // gzip encoding can be troublesome on old Androids
            if (Build.VERSION.SDK_INT < 19) {
                connection.setRequestProperty("Accept-Encoding", "identity");
            }

            // add authorization header from username / password if set
            if (username != null && password != null) {
                String authString = username + ":" + password;
                connection.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.toBase64String(authString.getBytes())
                );
            }
        } while (
          // Response Codes 300 - 399 are all redirects
          connection.getResponseCode() >= 300 &&
          connection.getResponseCode() < 400 &&
          // Only follow at most 20 redirects (default taken from Firefox)
          redirectCounter++ < 20
        );
        return connection;
    }

    private void setupConnection(boolean resumable) throws IOException {
        if (connection != null) {
            return;
        }
        connection = getConnection();

        if (resumable) {
            // partial file exists, resume the download
            connection.setRequestProperty("Range", "bytes=" + outputFile.length() + "-");
        }
    }

    // Testing in the emulator for me, showed that figuring out the
    // filesize took about 1 to 1.5 seconds.
    // To put this in context, downloading a repo of:
    //  - 400k takes ~6 seconds
    //  - 5k   takes ~3 seconds
    // on my connection. I think the 1/1.5 seconds is worth it,
    // because as the repo grows, the tradeoff will
    // become more worth it.
    @Override
    @TargetApi(24)
    public long totalDownloadSize() {
        if (Build.VERSION.SDK_INT < 24) {
            return connection.getContentLength();
        } else {
            return connection.getContentLengthLong();
        }
    }

    @Override
    public boolean hasChanged() {
        return newFileAvailableOnServer;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
