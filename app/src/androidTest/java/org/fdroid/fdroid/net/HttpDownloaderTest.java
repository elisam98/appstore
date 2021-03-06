
package org.fdroid.fdroid.net;

import org.fdroid.fdroid.ProgressListener;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpDownloaderTest {

    String[] urls = {
            "https://en.wikipedia.org/wiki/Index.html",
            "https://mirrors.kernel.org/debian/dists/stable/Release",
            "https://f-droid.org/repo/index.jar",
            // sites that use SNI for HTTPS
            "https://guardianproject.info/fdroid/repo/index.jar",
            //"https://microg.org/fdroid/repo/index.jar",
            //"https://grobox.de/fdroid/repo/index.jar",
    };

    private boolean receivedProgress;

    @Test
    public void downloadUninterruptedTest() throws IOException, InterruptedException {
        for (String urlString : urls) {
            URL url = new URL(urlString);
            File destFile = File.createTempFile("dl-", "");
            HttpDownloader httpDownloader = new HttpDownloader(url, destFile);
            httpDownloader.download();
            assertTrue(destFile.exists());
            assertTrue(destFile.canRead());
            destFile.deleteOnExit();
        }
    }

    @Test
    public void downloadUninterruptedTestWithProgress() throws IOException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        String urlString = "https://f-droid.org/repo/index.jar";
        receivedProgress = false;
        URL url = new URL(urlString);
        File destFile = File.createTempFile("dl-", "");
        final HttpDownloader httpDownloader = new HttpDownloader(url, destFile);
        httpDownloader.setListener(new ProgressListener() {
            @Override
            public void onProgress(URL sourceUrl, int bytesRead, int totalBytes) {
                receivedProgress = true;
            }
        });
        new Thread() {
            @Override
            public void run() {
                try {
                    httpDownloader.download();
                    latch.countDown();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                    fail();
                }
            }
        }.start();
        latch.await(100, TimeUnit.SECONDS); // either 2 progress reports or 100 seconds
        assertTrue(destFile.exists());
        assertTrue(destFile.canRead());
        assertTrue(receivedProgress);
        destFile.deleteOnExit();
    }

    @Test
    public void downloadHttpBasicAuth() throws IOException, InterruptedException {
        URL url = new URL("https://httpbin.org/basic-auth/myusername/supersecretpassword");
        File destFile = File.createTempFile("dl-", "");
        HttpDownloader httpDownloader = new HttpDownloader(url, destFile, "myusername", "supersecretpassword");
        httpDownloader.download();
        assertTrue(destFile.exists());
        assertTrue(destFile.canRead());
        destFile.deleteOnExit();
    }

    @Test(expected = IOException.class)
    public void downloadHttpBasicAuthWrongPassword() throws IOException, InterruptedException {
        URL url = new URL("https://httpbin.org/basic-auth/myusername/supersecretpassword");
        File destFile = File.createTempFile("dl-", "");
        HttpDownloader httpDownloader = new HttpDownloader(url, destFile, "myusername", "wrongpassword");
        httpDownloader.download();
        assertFalse(destFile.exists());
        destFile.deleteOnExit();
    }

    @Test(expected = IOException.class)
    public void downloadHttpBasicAuthWrongUsername() throws IOException, InterruptedException {
        URL url = new URL("https://httpbin.org/basic-auth/myusername/supersecretpassword");
        File destFile = File.createTempFile("dl-", "");
        HttpDownloader httpDownloader = new HttpDownloader(url, destFile, "wrongusername", "supersecretpassword");
        httpDownloader.download();
        assertFalse(destFile.exists());
        destFile.deleteOnExit();
    }

    @Test
    public void downloadThenCancel() throws IOException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(2);
        URL url = new URL("https://f-droid.org/repo/index.jar");
        File destFile = File.createTempFile("dl-", "");
        final HttpDownloader httpDownloader = new HttpDownloader(url, destFile);
        httpDownloader.setListener(new ProgressListener() {
            @Override
            public void onProgress(URL sourceUrl, int bytesRead, int totalBytes) {
                receivedProgress = true;
                latch.countDown();
            }
        });
        new Thread() {
            @Override
            public void run() {
                try {
                    httpDownloader.download();
                    fail();
                } catch (IOException e) {
                    e.printStackTrace();
                    fail();
                } catch (InterruptedException e) {
                    // success!
                }
            }
        }.start();
        latch.await(100, TimeUnit.SECONDS); // either 2 progress reports or 100 seconds
        httpDownloader.cancelDownload();
        assertTrue(receivedProgress);
        destFile.deleteOnExit();
    }
}
