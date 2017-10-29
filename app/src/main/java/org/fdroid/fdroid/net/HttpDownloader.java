package org.fdroid.fdroid.net;

import android.text.TextUtils;
import com.nostra13.universalimageloader.core.download.BaseImageDownloader;
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

public class HttpDownloader extends Downloader {
    private static final String TAG = "HttpDownloader";

    private static final String HEADER_FIELD_ETAG = "ETag";

    private final String username;
    private final String password;
    private HttpURLConnection connection;
    private boolean newFileAvailableOnServer;

    HttpDownloader(URL url, File destFile)
            throws FileNotFoundException, MalformedURLException {
        this(url, destFile, null, null);
    }

    /**
     * Create a downloader that can authenticate via HTTP Basic Auth using the supplied
     * {@code username} and {@code password}.
     *
     * @param url      The file to download
     * @param destFile Where the download is saved
     * @param username Username for HTTP Basic Auth, use {@code null} to ignore
     * @param password Password for HTTP Basic Auth, use {@code null} to ignore
     * @throws FileNotFoundException
     * @throws MalformedURLException
     */
    HttpDownloader(URL url, File destFile, String username, String password)
            throws FileNotFoundException, MalformedURLException {
        super(url, destFile);

        this.username = username;
        this.password = password;
    }

    /**
     * Note: Doesn't follow redirects (as far as I'm aware).
     * {@link BaseImageDownloader#getStreamFromNetwork(String, Object)} has an implementation worth
     * checking out that follows redirects up to a certain point. I guess though the correct way
     * is probably to check for a loop (keep a list of all URLs redirected to and if you hit the
     * same one twice, bail with an exception).
     *
     * @throws IOException
     */
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
                    Utils.debugLog(TAG, sourceUrl + " is cached, not downloading");
                    return;
                }
                newFileAvailableOnServer = true;
                break;
            case 404:
                notFound = true;
                return;
            default:
                Utils.debugLog(TAG, "HEAD check of " + sourceUrl + " returned " + statusCode + ": "
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
        Utils.debugLog(TAG, "downloading " + sourceUrl + " (is resumable: " + resumable + ")");
        downloadFromStream(8192, resumable);
        cacheTag = connection.getHeaderField(HEADER_FIELD_ETAG);
    }

    private boolean isSwapUrl() {
        String host = sourceUrl.getHost();
        return sourceUrl.getPort() > 1023 // only root can use <= 1023, so never a swap repo
                && host.matches("[0-9.]+") // host must be an IP address
                && FDroidApp.subnetInfo.isInRange(host); // on the same subnet as we are
    }

    private HttpURLConnection getConnection() throws SocketTimeoutException, IOException {
        HttpURLConnection connection;
        if (isSwapUrl()) {
            // swap never works with a proxy, its unrouted IP on the same subnet
            connection = (HttpURLConnection) sourceUrl.openConnection();
        } else {
            connection = NetCipher.getHttpURLConnection(sourceUrl);
        }

        connection.setRequestProperty("User-Agent", "F-Droid " + BuildConfig.VERSION_NAME);
        connection.setConnectTimeout(getTimeout());

        if (username != null && password != null) {
            // add authorization header from username / password if set
            String authString = username + ":" + password;
            connection.setRequestProperty("Authorization", "Basic " + Base64.toBase64String(authString.getBytes()));
        }
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
    public int totalDownloadSize() {
        return connection.getContentLength();
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
