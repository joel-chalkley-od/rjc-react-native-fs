package com.rnfs;

import android.os.AsyncTask;
import android.webkit.MimeTypeMap;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.NoSuchKeyException;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Uploader extends AsyncTask<UploadParams, int[], UploadResult> {
    private UploadParams mParams;
    private UploadResult res;
    private AtomicBoolean mAbort = new AtomicBoolean(false);

    @Override
    protected UploadResult doInBackground(UploadParams... uploadParams) {
        mParams = uploadParams[0];
        res = new UploadResult();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    upload(mParams, res);
                    mParams.onUploadComplete.onUploadComplete(res);
                } catch (Exception e) {
                    res.exception = e;
                    mParams.onUploadComplete.onUploadComplete(res);
                }
            }
        }).start();
        return res;
    }

    private void upload(UploadParams params, UploadResult result) throws Exception {
        HttpURLConnection connection = null;
        DataOutputStream request = null;
        String crlf = "\r\n";
        String twoHyphens = "--";
        String boundary = "*****";
        String tail = crlf + twoHyphens + boundary + twoHyphens + crlf;
        String metaData = "", stringData = "";
        String[] fileHeader;
        int bufferSize, totalSize, byteRead, statusCode, bufferAvailable, progress,contentLength;
        int fileCount = 0;
        long totalFileLength = 0;
        BufferedInputStream responseStream = null;
        BufferedReader responseStreamReader = null;
        int maxBufferSize = 1 * 1024 * 1024;
        String name, filename, filetype;
        try {
            connection = (HttpURLConnection) params.src.openConnection();
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            ReadableMapKeySetIterator headerIterator = params.headers.keySetIterator();
            connection.setRequestMethod(params.method);
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            while (headerIterator.hasNextKey()) {
                String key = headerIterator.nextKey();
                String value = params.headers.getString(key);
                connection.setRequestProperty(key, value);
            }

            ReadableMapKeySetIterator fieldsIterator = params.fields.keySetIterator();

            while (fieldsIterator.hasNextKey()) {
                String key = fieldsIterator.nextKey();
                String value = params.fields.getString(key);
                metaData += twoHyphens + boundary + crlf + "Content-Disposition: form-data; name=\"" + key + "\"" + crlf + crlf + value +crlf;
            }
            stringData += metaData;
            fileHeader = new String[params.files.toArray().length];
            System.out.println(params.files.toArray().length);
            for (ReadableMap map : params.files) {
                try {
                    name = map.getString("name");
                    filename = map.getString("filename");
                    filetype = map.getString("filetype");
                } catch (NoSuchKeyException e) {
                    name = map.getString("name");
                    filename = map.getString("filename");
                    filetype = getMimeType(map.getString("filepath"));
                }
                File file = new File(map.getString("filepath"));
                String fileHeaderType = twoHyphens + boundary + crlf +
                        "Content-Disposition: form-data; name=\"" + name + "\";filename=\"" + filename + "\"" + crlf +
                        "Content-Type: " + filetype + crlf ;
                long fileLength = file.length() + tail.length();
                totalFileLength += fileLength;
                String fileLengthHeader = "Content-length: " + fileLength + crlf;
                fileHeader[fileCount] = fileHeaderType + fileLengthHeader + crlf;
                stringData += fileHeaderType + fileLengthHeader + crlf;
                fileCount++;
            }
            fileCount = 0;
            mParams.onUploadBegin.onUploadBegin();
            long requestLength = totalFileLength + stringData.length();
            connection.setRequestProperty("Content-length", "" + requestLength);
            connection.setFixedLengthStreamingMode((int)requestLength);
            connection.connect();

            request = new DataOutputStream(connection.getOutputStream());
            request.writeBytes(metaData);
            for (ReadableMap map : params.files) {
                request.writeBytes(fileHeader[fileCount]);
                File file = new File(map.getString("filepath"));
                FileInputStream fis = new FileInputStream(file);
                int fileLength = (int) file.length();
                int bytes_read = 0;
                int bytesReadTotal = 0;
                int buffer_size = fileLength / 100;
                byte[] buffer = new byte[buffer_size];
                while ((bytes_read = fis.read(buffer, 0, Math.min(fileLength - bytesReadTotal, buffer_size))) > 0) {
                    request.write(buffer, 0, bytes_read);
                    bytesReadTotal += bytes_read;
                    mParams.onUploadProgress.onUploadProgress(fileCount + 1, fileLength, bytesReadTotal);
                }
                request.writeBytes(crlf);
                fileCount++;
            }
            request.writeBytes(tail);
            request.flush();
            request.close();

            responseStream = new BufferedInputStream(connection.getInputStream());
            responseStreamReader = new BufferedReader(new InputStreamReader(responseStream));
            WritableMap responseHeaders = Arguments.createMap();
            Map<String, List<String>> map = connection.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                int count = 0;
                responseHeaders.putString(entry.getKey(), entry.getValue().get(count));
            }
            StringBuilder stringBuilder = new StringBuilder();
            String line = "";

            while ((line = responseStreamReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }

            String response = stringBuilder.toString();
            statusCode = connection.getResponseCode();
            res.headers = responseHeaders;
            res.body = response;
            res.statusCode = statusCode;
        } finally {
            if (connection != null)
                connection.disconnect();
            if (request != null)
                request.close();
            if (responseStream != null)
                responseStream.close();
            if (responseStreamReader != null)
                responseStreamReader.close();
        }
    }

    protected String getMimeType(String path) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(path);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    protected void stop() {
        mAbort.set(true);
    }

}
