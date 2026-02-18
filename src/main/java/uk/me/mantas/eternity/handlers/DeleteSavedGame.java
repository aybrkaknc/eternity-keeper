package uk.me.mantas.eternity.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONStringer;
import uk.me.mantas.eternity.Logger;

import java.io.File;
import java.io.IOException;

public class DeleteSavedGame extends CefMessageRouterHandlerAdapter {
    private static final Logger logger = Logger.getLogger(DeleteSavedGame.class);

    @Override
    public boolean onQuery(
            CefBrowser browser, long id, String request, boolean persistent, CefQueryCallback callback) {

        File saveFile = new File(request);
        if (!saveFile.exists()) {
            callback.failure(404, error("Save file not found: " + request));
            return true;
        }

        // Debug info about the file
        logger.info("ATTEMPTING DELETE: %s (Is File: %b, Is Directory: %b, Writable: %b)%n",
                saveFile.getAbsolutePath(), saveFile.isFile(), saveFile.isDirectory(), saveFile.canWrite());

        try {
            if (!saveFile.canWrite()) {
                logger.warn("File appears read-only, attempting to set writable: %s%n", request);
                saveFile.setWritable(true);
            }

            try {
                FileUtils.forceDelete(saveFile);
            } catch (IOException e) {
                logger.warn("Initial delete failed (%s). Triggering GC and retrying...%n", e.getMessage());
                // Force garbage collection as a last resort for file locks on Windows
                System.gc();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    // ignore
                }

                // Retry with native IO
                try {
                    java.nio.file.Files.delete(saveFile.toPath());
                } catch (IOException nioe) {
                    logger.error("NIO delete also failed (%s). One last try with forceDelete...%n", nioe.getMessage());
                    FileUtils.forceDelete(saveFile);
                }
            }

            if (saveFile.exists()) {
                logger.error("FATAL: File still exists after exhaustive delete attempts: %s%n", request);
                logger.error("File Attributes - Readable: %b, Writable: %b, Executable: %b%n",
                        saveFile.canRead(), saveFile.canWrite(), saveFile.canExecute());
                callback.failure(500,
                        error("File delete reported success but file still exists. Check if Steam Cloud is restoring the file."));
            } else {
                logger.info("SUCCESS: Deleted save file: %s%n", request);
                callback.success(success());
            }

        } catch (IOException e) {
            logger.error("Failed to delete save file '%s': %s%n", request, e.getMessage());
            callback.failure(500, error("Unable to delete save file: " + e.getMessage()));
        }

        return true;
    }

    @Override
    public void onQueryCanceled(CefBrowser browser, long id) {
        logger.error("Query #%d was cancelled.%n", id);
    }

    private String success() {
        return new JSONStringer()
                .object()
                .key("success").value(true)
                .endObject()
                .toString();
    }

    private String error(String message) {
        return new JSONStringer()
                .object()
                .key("error").value(message)
                .endObject()
                .toString();
    }
}
