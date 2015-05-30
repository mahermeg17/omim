package com.mapswithme.maps.downloader;

import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import com.mapswithme.util.Constants;
import com.mapswithme.util.StringUtils;
import com.mapswithme.util.Utils;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class DownloadChunkTask extends AsyncTask<Void, byte[], Boolean>
{
  private static final String TAG = "DownloadChunkTask";

  private static final int TIMEOUT_IN_SECONDS = 60;

  private final long mHttpCallbackID;
  private final String mUrl;
  private final long mBeg;
  private final long mEnd;
  private final long mExpectedFileSize;
  private byte[] mPostBody;
  private final String mUserAgent;

  private final int NOT_SET = -1;
  private final int IO_ERROR = -2;
  private final int INVALID_URL = -3;
  private final int WRITE_ERROR = -4;
  private final int FILE_SIZE_CHECK_FAILED = -5;

  private int mHttpErrorCode = NOT_SET;
  private long mDownloadedBytes = 0;

  private static Executor sExecutors = Executors.newFixedThreadPool(4);

  native boolean onWrite(long httpCallbackID, long beg, byte[] data, long size);

  native void onFinish(long httpCallbackID, long httpCode, long beg, long end);

  public DownloadChunkTask(long httpCallbackID, String url, long beg, long end,
                           long expectedFileSize, byte[] postBody, String userAgent)
  {
    mHttpCallbackID = httpCallbackID;
    mUrl = url;
    mBeg = beg;
    mEnd = end;
    mExpectedFileSize = expectedFileSize;
    mPostBody = postBody;
    mUserAgent = userAgent;
  }

  @Override
  protected void onPreExecute()
  {
  }

  private long getChunkID() { return mBeg; }

  @Override
  protected void onPostExecute(Boolean success)
  {
    //Log.i(TAG, "Writing chunk " + getChunkID());

    // It seems like onPostExecute can be called (from GUI thread queue)
    // after the task was cancelled in destructor of HttpThread.
    // Reproduced by Samsung testers: touch Try Again for many times from
    // start activity when no connection is present.

    if (!isCancelled())
      onFinish(mHttpCallbackID, success ? 200 : mHttpErrorCode, mBeg, mEnd);
  }

  @Override
  protected void onProgressUpdate(byte[]... data)
  {
    if (!isCancelled())
    {
      // Use progress event to save downloaded bytes.
      if (onWrite(mHttpCallbackID, mBeg + mDownloadedBytes, data[0], data[0].length))
        mDownloadedBytes += data[0].length;
      else
      {
        // Cancel downloading and notify about error.
        cancel(false);
        onFinish(mHttpCallbackID, WRITE_ERROR, mBeg, mEnd);
      }
    }
  }

  void start()
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
      executeOnExecutor(sExecutors, (Void[]) null);
    else
      execute((Void[]) null);
  }

  static long parseContentRange(String contentRangeValue)
  {
    if (contentRangeValue != null)
    {
      final int slashIndex = contentRangeValue.lastIndexOf('/');
      if (slashIndex >= 0)
      {
        try
        {
          return Long.parseLong(contentRangeValue.substring(slashIndex + 1));
        } catch (final NumberFormatException ex)
        {
          // Return -1 at the end of function
        }
      }
    }
    return -1;
  }

  @Override
  protected Boolean doInBackground(Void... p)
  {
    //Log.i(TAG, "Start downloading chunk " + getChunkID());

    HttpURLConnection urlConnection = null;

    try
    {
      final URL url = new URL(mUrl);
      urlConnection = (HttpURLConnection) url.openConnection();

      if (isCancelled())
        return false;

      urlConnection.setUseCaches(false);
      urlConnection.setConnectTimeout(TIMEOUT_IN_SECONDS * 1000);
      urlConnection.setReadTimeout(TIMEOUT_IN_SECONDS * 1000);

      // Set user agent with unique client id
      urlConnection.setRequestProperty("User-Agent", mUserAgent);

      // use Range header only if we don't download whole file from start
      if (!(mBeg == 0 && mEnd < 0))
      {
        if (mEnd > 0)
          urlConnection.setRequestProperty("Range", StringUtils.formatUsingUsLocale("bytes=%d-%d", mBeg, mEnd));
        else
          urlConnection.setRequestProperty("Range", StringUtils.formatUsingUsLocale("bytes=%d-", mBeg));
      }

      final Map<?, ?> requestParams = urlConnection.getRequestProperties();

      if (mPostBody != null)
      {
        urlConnection.setDoOutput(true);
        urlConnection.setFixedLengthStreamingMode(mPostBody.length);

        final DataOutputStream os = new DataOutputStream(urlConnection.getOutputStream());
        os.write(mPostBody);
        os.flush();
        mPostBody = null;
        Utils.closeStream(os);
      }

      if (isCancelled())
        return false;

      final int err = urlConnection.getResponseCode();
      // @TODO We can handle redirect (301, 302 and 307) here and display redirected page to user,
      // to avoid situation when downloading is always failed by "unknown" reason
      // When we didn't ask for chunks, code should be 200
      // When we asked for a chunk, code should be 206
      final boolean isChunk = !(mBeg == 0 && mEnd < 0);
      if ((isChunk && err != HttpURLConnection.HTTP_PARTIAL) || (!isChunk && err != HttpURLConnection.HTTP_OK))
      {
        // we've set error code so client should be notified about the error
        mHttpErrorCode = FILE_SIZE_CHECK_FAILED;
        Log.w(TAG, "Error for " + urlConnection.getURL() +
            ": Server replied with code " + err +
            ", aborting download. " + Utils.mapPrettyPrint(requestParams));
        return false;
      }

      // Check for content size - are we downloading requested file or some router's garbage?
      if (mExpectedFileSize > 0)
      {
        long contentLength = parseContentRange(urlConnection.getHeaderField("Content-Range"));
        if (contentLength < 0)
          contentLength = urlConnection.getContentLength();

        // Check even if contentLength is invalid (-1), in this case it's not our server!
        if (contentLength != mExpectedFileSize)
        {
          // we've set error code so client should be notified about the error
          mHttpErrorCode = FILE_SIZE_CHECK_FAILED;
          Log.w(TAG, "Error for " + urlConnection.getURL() +
              ": Invalid file size received (" + contentLength + ") while expecting " + mExpectedFileSize +
              ". Aborting download.");
          return false;
        }
        // @TODO Else display received web page to user - router is redirecting us to some page
      }

      return downloadFromStream(new BufferedInputStream(urlConnection.getInputStream(), 65536));
    } catch (final MalformedURLException ex)
    {
      Log.d(TAG, "Invalid url: " + mUrl);

      // Notify the client about error
      mHttpErrorCode = INVALID_URL;
      return false;
    } catch (final IOException ex)
    {
      Log.d(TAG, "IOException in doInBackground for URL: " + mUrl, ex);

      // Notify the client about error
      mHttpErrorCode = IO_ERROR;
      return false;
    } finally
    {
      //Log.i(FRAGMENT_TAG, "End downloading chunk " + getChunkID());

      if (urlConnection != null)
        urlConnection.disconnect();
      else
        mHttpErrorCode = IO_ERROR;
    }
  }

  /// Because of timeouts in InpetStream.read (for bad connection),
  /// try to introduce dynamic buffer size to read in one query.
  private boolean downloadFromStream(InputStream stream)
  {
    final int arrSize[] = {64, 32, 1};
    int ret = -1;

    for (int i = 0; i < arrSize.length; ++i)
    {
      try
      {
        // download chunk from stream
        ret = downloadFromStreamImpl(stream, arrSize[i] * Constants.KB);
        break;
      } catch (final IOException ex)
      {
        Log.d(TAG, "IOException in downloadFromStream for chunk size: " + arrSize[i], ex);
      }
    }

    if (ret < 0)
    {
      // notify the client about error
      mHttpErrorCode = IO_ERROR;
    }

    Utils.closeStream(stream);

    return (ret == 0);
  }

  /// @return
  /// 0 - download successful;
  /// 1 - download canceled;
  /// -1 - some error occurred;
  private int downloadFromStreamImpl(InputStream stream, int bufferSize) throws IOException
  {
    final byte[] tempBuf = new byte[bufferSize];

    int readBytes;
    while ((readBytes = stream.read(tempBuf)) > 0)
    {
      if (isCancelled())
        return 1;

      final byte[] chunk = new byte[readBytes];
      System.arraycopy(tempBuf, 0, chunk, 0, readBytes);

      publishProgress(chunk);
    }

    // -1 - means the end of the stream (success), else - some error occurred
    return (readBytes == -1 ? 0 : -1);
  }
}
