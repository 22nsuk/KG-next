package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** SSH transport for a remote GTP engine. */
public class SSHController extends SshEngineController {
  private final Leelaz standaloneOwner;
  private final BooleanSupplier mayPresentFailure;
  private final Consumer<String> showDiagnostic;

  public SSHController(Leelaz owner, String ip, String port) {
    super(ip, port);
    standaloneOwner = owner;
    mayPresentFailure = () -> !isClosed();
    showDiagnostic = message -> owner.tryToDignostic(message, true);
  }

  SSHController(
      String ip, String port, BooleanSupplier mayPresentFailure, Consumer<String> showDiagnostic) {
    super(ip, port);
    standaloneOwner = null;
    this.mayPresentFailure = mayPresentFailure;
    this.showDiagnostic = showDiagnostic;
  }

  @Override
  protected void onAuthenticationRejected() {
    publishStartupFailure(
        () -> Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed")));
  }

  @Override
  protected void onConnectionFailed(String message) {
    publishStartupFailure(
        () ->
            showDiagnostic.accept(
                Lizzie.resourceBundle.getString("SSHController.engineFailed") + ": " + message));
  }

  private void publishStartupFailure(Runnable showFailure) {
    // Managed starts publish state through their request owner. Standalone controllers keep
    // their original failure-state transition.
    if (standaloneOwner != null) standaloneOwner.isLoaded = false;
    // A failed start closes its SSH transport before the EDT necessarily gets to the failure.
    publishFailure(
        mayPresentFailure,
        () -> {
          showFailure.run();
          // A shutdown or replacement can run while the modal failure dialog is open.
          if (mayPresentFailure.getAsBoolean()) LizzieFrame.openMoreEngineDialog();
        });
  }
}
