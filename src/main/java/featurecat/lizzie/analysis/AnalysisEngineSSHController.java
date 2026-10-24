package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;

/** SSH transport for a remote KataGo analysis engine. */
public class AnalysisEngineSSHController extends SshEngineController {
  private final AnalysisEngine owner;
  private final boolean isPreLoad;

  public AnalysisEngineSSHController(
      AnalysisEngine owner, String ip, String port, boolean isPreLoad) {
    super(ip, port);
    this.owner = owner;
    this.isPreLoad = isPreLoad;
  }

  @Override
  protected void onAuthenticationRejected() {
    if (!isPreLoad) {
      publishFailure(
          () -> Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed")));
    }
  }

  @Override
  protected void onConnectionFailed(String message) {
    if (!isPreLoad) {
      publishFailure(
          () ->
              owner.tryToDignostic(
                  Lizzie.resourceBundle.getString("SSHController.engineFailed") + ": " + message));
    }
  }

  @Override
  public void close() {
    owner.javaSSHClosed = true;
    owner.isNormalEnd = true;
    super.close();
  }
}
