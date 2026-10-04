package io.github.docmorphic.cmuxapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Separate test APK/UID; platform-only so it needs none of the target app's classes. */
public final class GrantFixtureProvider extends ContentProvider {
    private File image;
    @Override public boolean onCreate() {
        image = new File(getContext().getCacheDir(), "keyboard-grant-fixture.png");
        Bitmap bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(image)) {
            bitmap.eraseColor(Color.YELLOW);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG fixture failed");
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { bitmap.recycle(); }
        return true;
    }
    static void validate(Uri uri) {
        if (uri.getPath() == null || !uri.getPath().matches("/image/[a-f0-9-]+\\.png")) {
            throw new IllegalArgumentException("Unknown fixture path");
        }
    }
    @Override public String getType(Uri uri) { validate(uri); return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        validate(uri);
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        validate(uri);
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "keyboard-grant.png";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = image.length();
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
