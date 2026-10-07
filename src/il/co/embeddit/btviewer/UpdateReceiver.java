package il.co.embeddit.btviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Result of the install session. Silent installs finish without calling this
 * beyond SUCCESS; when Android does want a confirmation (first self-update
 * after a manual install) it hands us the screen to show.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Diag.log("update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
