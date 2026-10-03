package io.github.docmorphic.cmuxapp.noticespike;

import android.app.Activity;
import android.os.Bundle;
import org.mozilla.geckoview.GeckoView;

public final class ProbeActivity extends Activity {
    public GeckoView view;
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        view = new GeckoView(this);
        setContentView(view);
    }
}
