package io.github.docmorphic.cmuxapp;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

/** Installed and enabled only for emulator instrumentation; restored after each test. */
public final class GrantFixtureKeyboard extends InputMethodService {
    static GrantFixtureKeyboard running;
    boolean inputActive;
    @Override public void onCreate() { super.onCreate(); running = this; }
    @Override public void onDestroy() { if (running == this) running = null; super.onDestroy(); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting); inputActive = true;
    }
    @Override public void onFinishInput() { inputActive = false; super.onFinishInput(); }
    @Override public View onCreateInputView() {
        TextView view = new TextView(this);
        view.setText("cmux URI grant test keyboard");
        view.setPadding(12, 12, 12, 12);
        return view;
    }
}
