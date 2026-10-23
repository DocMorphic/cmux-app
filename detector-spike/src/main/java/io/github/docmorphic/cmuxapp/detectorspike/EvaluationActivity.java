package io.github.docmorphic.cmuxapp.detectorspike;

/** A normal launcher entry point for the isolated SDK host. No companion data. */
public final class EvaluationActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("Synthetic date/address detector evaluation");
        setContentView(text);
    }
}
