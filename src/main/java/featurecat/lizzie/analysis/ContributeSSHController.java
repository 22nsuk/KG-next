package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.ssh.SshCommandSession;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** SSH transport for a remote KataGo contribute process. */
public class ContributeSSHController extends SshEngineController {
  private final ContributeEngine owner;

  public ContributeSSHController(ContributeEngine owner, String ip, String port) {
    super(ip, port);
    this.owner = owner;
  }

  @Override
  protected void onAuthenticationRejected() {
    publishFailure(
        () -> Utils.showMsg(Lizzie.resourceBundle.getString("SSHController.connectFailed")));
  }

  @Override
  protected void onConnectionFailed(String message) {
    publishFailure(
        () ->
            owner.tryToDignostic(
                Lizzie.resourceBundle.getString("SSHController.engineFailed") + ": " + message));
  }

  @Override
  public void close() {
    owner.javaSSHClosed = true;
    super.close();
  }

  @Override
  protected void closeSession(SshCommandSession current) {
    try {
      // EOF does not stop KataGo contribution workers. Retire this session, never its successor.
      current.stdin().write("forcequit\n".getBytes(StandardCharsets.UTF_8));
      current.stdin().flush();
    } catch (IOException closed) {
      // An exited peer may have closed stdin; the transport still belongs to this controller.
    } finally {
      current.close();
    }
  }
}
