package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;

/** SSH transport for a remote GTP engine. */
public class SSHController extends SshEngineController {
  private final Leelaz owner;

  public SSHController(Leelaz owner, String ip, String port) {
    super(ip, port);
    this.owner = owner;
  }

  @Override
  protected void onAuthenticationRejected() {
    owner.isLoaded = false;
    publishFailure(
        () -> {
          Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed"));
          LizzieFrame.openMoreEngineDialog();
        });
  }

  @Override
  protected void onConnectionFailed(String message) {
    owner.isLoaded = false;
    publishFailure(
        () -> {
          owner.tryToDignostic(
              Lizzie.resourceBundle.getString("SSHController.engineFailed") + ": " + message, true);
          LizzieFrame.openMoreEngineDialog();
        });
  }
}
