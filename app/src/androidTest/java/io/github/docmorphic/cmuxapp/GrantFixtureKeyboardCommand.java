package io.github.docmorphic.cmuxapp;

import android.content.BroadcastReceiver;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import java.util.Arrays;

/** Explicit test command; can only send this test APK's fixed image to the target app. */
public final class GrantFixtureKeyboardCommand extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        GrantFixtureKeyboard service = GrantFixtureKeyboard.running;
        String target = context.getPackageName().replaceFirst("\\.test$", "");
        EditorInfo editor = service != null ? service.getCurrentInputEditorInfo() : null;
        InputConnection connection = service != null ? service.getCurrentInputConnection() : null;
        // The host can keep a fallback InputConnection after the actual editor is gone.
        boolean ready = service != null && service.inputActive && editor != null
            && target.equals(editor.packageName) && connection != null && editor.contentMimeTypes != null
            && Arrays.asList(editor.contentMimeTypes).contains("image/*");
        Bundle response = new Bundle();
        response.putBoolean("ready", ready);
        response.putInt("uid", Process.myUid());
        response.putStringArray("types", editor != null ? editor.contentMimeTypes : null);
        String rawUri = intent.getStringExtra("uri");
        if (ready && rawUri != null) {
            Uri uri = Uri.parse(rawUri);
            if (!(context.getPackageName() + ".keyboard-grants").equals(uri.getAuthority())) {
                throw new IllegalArgumentException("Only the fixed fixture provider may be used");
            }
            GrantFixtureProvider.validate(uri);
            response.putBoolean("accepted", connection.commitContent(new InputContentInfo(uri,
                new ClipDescription("Fixture image", new String[] {"image/png"}), null),
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null));
        }
        setResultExtras(response);
    }
}
