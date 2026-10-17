package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.RemoteEngineData;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.ExportSanitizer;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.jdesktop.swingx.util.OS;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ContributeEngine {
  private static final java.util.regex.Pattern STARTING_GAME =
      java.util.regex.Pattern.compile(
          "(?:\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[^ ]*: )?Starting game \\d+.*");

  private ArrayList<ContributeGameInfo> contributeGames;
  private ArrayList<ContributeUnParseGameInfo> unParseGameInfos;
  public int watchingGameIndex = -1;
  private int changeWatchingGameIndex = -1;
  private Thread watchGameThread;
  private ContributeGameInfo currentWatchGame;

  private Process process;
  private boolean isNormalEnd = false;

  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private BufferedReader errorStream;

  private String engineCommand;
  private final ScheduledExecutorService lifecycle =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "contribution-lifecycle");
            thread.setDaemon(true);
            return thread;
          });
  private List<String> commands;

  private volatile EngineStartupDiagnostics.Attempt currentAttempt;
  private Launch active;
  private volatile boolean stopped;
  private int launches;
  private final ExportSanitizer sanitizer = new ExportSanitizer();

  private enum Failure {
    OTHER,
    DISTRIBUTED_UNSUPPORTED,
    BUILD_REJECTED,
    CA_CERTIFICATES
  }

  private static final class Launch {
    final EngineStartupDiagnostics.Attempt diagnostic;
    Process process;
    ContributeSSHController ssh;
    BufferedReader stdout;
    BufferedReader stderr;
    boolean stdoutEnded;
    boolean stderrEnded;
    boolean draining;
    boolean terminal;
    boolean terminated;
    boolean presented;
    int pendingUi;
    long deadline;
    Failure failure = Failure.OTHER;
    String error = "";

    Launch(EngineStartupDiagnostics.Attempt diagnostic) {
      this.diagnostic = diagnostic;
    }

    boolean permanent() {
      return failure == Failure.DISTRIBUTED_UNSUPPORTED || failure == Failure.BUILD_REJECTED;
    }
  }

  private boolean useJavaSSH = false;
  private ContributeSSHController javaSSH;
  private String ip;
  private String port;
  private String userName;
  private String password;
  private boolean useKeyGen;
  private String keyGenPath;
  public boolean javaSSHClosed;
  private String courseFile = "";
  private String timeStamp;
  private final boolean pureCommand =
      Lizzie.config.contributeUseCommand && Lizzie.config.contributeUsePureCommand;
  private boolean addCacerts = false;
  // private boolean useProcessHandler = false;
  private boolean isSlowQuiting = false;
  private boolean getVersion = false;

  public boolean paused = false;
  private long lastPauseResumeTime;

  public ContributeEngine() {
    if (Lizzie.config.contributeUseCommand) {
      engineCommand = Lizzie.config.contributeCommand;
      if (!Lizzie.config.contributeUsePureCommand) {
        if (!engineCommand.contains("-override")) engineCommand += " -override-config ";
        if (!engineCommand.contains(" -config")) {
          engineCommand += " \"serverUrl=https://katagotraining.org/\",";
        }
      }
    } else {
      engineCommand = Lizzie.config.contributeEnginePath + " contribute";
      try {
        File file = new File("");
        courseFile = file.getCanonicalPath();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      boolean useConfigFile = Lizzie.config.contributeConfigPath.trim().length() > 0;
      if (useConfigFile) engineCommand += " -config " + Lizzie.config.contributeConfigPath;
      engineCommand += " -override-config ";
      if (!useConfigFile) engineCommand += "\"serverUrl=https://katagotraining.org/\",";
    }

    if (!Lizzie.config.contributeUseCommand || !Lizzie.config.contributeUsePureCommand) {
      engineCommand += "\"username=" + Lizzie.config.contributeUserName + "\",";
      engineCommand += "\"password=" + Lizzie.config.contributePassword + "\",";
      engineCommand += "\"maxSimultaneousGames=" + Lizzie.config.contributeBatchGames + "\",";
      engineCommand +=
          "\"includeOwnership=" + (Lizzie.config.contributeShowEstimate ? "true" : "false") + "\",";
      if (Lizzie.config.contributeDisableRatingMatches) engineCommand += "\"maxRatingMatches=0\",";
      engineCommand += "\"logGamesAsJson=true\"";
    }
    RemoteEngineData remoteData = Utils.getContributeRemoteEngineData();
    this.useJavaSSH = Lizzie.config.contributeUseCommand && remoteData.useJavaSSH;
    this.ip = remoteData.ip;
    this.port = remoteData.port;
    this.userName = remoteData.userName;
    this.password = remoteData.password;
    this.useKeyGen = remoteData.useKeyGen;
    this.keyGenPath = remoteData.keyGenPath;
    if (!this.useJavaSSH && KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      return;
    }

    contributeGames = new ArrayList<ContributeGameInfo>();
    unParseGameInfos = new ArrayList<ContributeUnParseGameInfo>();
    timeStamp = new SimpleDateFormat("yyyy-MM-dd-HH").format(new Date());
    Lizzie.frame.isContributing = true;
    Lizzie.board.clearWithoutPrimaryEngineForwarding();
    Menu.engineMenu.setText(Lizzie.resourceBundle.getString("Contribute.engineMenu.contributing"));
    Lizzie.frame.refresh();
    startEngine();
  }

  private synchronized void startEngine() {
    if (stopped) return;
    launches++;
    hasEternalTip = false;
    getVersion = false;
    paused = false;
    CommandLaunchHelper.LaunchSpec launchSpec =
        CommandLaunchHelper.prepare(Utils.splitCommand(engineCommand));
    commands = launchSpec.getCommandParts();
    if (!this.useJavaSSH && KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      return;
    }
    String engineId =
        EngineObservation.restartInstance(
            this, "CONTRIBUTE", EngineStartupBootstrap.factsFor(engineCommand, "CONTRIBUTE"));
    EngineStartupDiagnostics.Attempt attempt =
        EngineStartupDiagnostics.getDefault()
            .begin(engineId, "CONTRIBUTE", commands, !this.useJavaSSH);
    this.currentAttempt = attempt;
    Launch launch = new Launch(attempt);
    active = launch;
    if (this.useJavaSSH) {
      this.javaSSH = new ContributeSSHController(this, this.ip, this.port);
      launch.ssh = this.javaSSH;
      boolean loginStatus = false;
      if (this.useKeyGen) {
        loginStatus =
            this.javaSSH
                .loginByFileKey(this.engineCommand, this.userName, new File(this.keyGenPath))
                .booleanValue();
      } else {
        loginStatus =
            this.javaSSH.login(this.engineCommand, this.userName, this.password).booleanValue();
      }
      if (loginStatus) {
        this.inputStream = new BufferedReader(new InputStreamReader(this.javaSSH.getStdout()));
        this.outputStream = new BufferedOutputStream(this.javaSSH.getStdin());
        this.errorStream = new BufferedReader(new InputStreamReader(this.javaSSH.getSterr()));
        javaSSHClosed = false;
      } else {
        javaSSHClosed = true;
        launch.error = "SSH login failed";
        attempt.fail("process-create", launch.error);
        beginDrain(launch);
        return;
      }
    } else {
      ProcessBuilder processBuilder = new ProcessBuilder(commands);
      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
      processBuilder.redirectErrorStream(false);
      attempt.capture(processBuilder);
      try {
        process = processBuilder.start();
        launch.process = process;
        attempt.attachProcess(process);
        AnalysisResourceCoordinator.processStarted(
            this, AnalysisResourceCoordinator.Purpose.OTHER, engineCommand, process, true);
      } catch (IOException e) {
        launch.error = e.toString();
        attempt.fail("process-create", launch.error);
        beginDrain(launch);
        return;
      }
      initializeStreams();
    }
    launch.stdout = inputStream;
    launch.stderr = errorStream;
    isNormalEnd = false;
    reader(launch, launch.stdout, false);
    reader(launch, launch.stderr, true);
    if (launch.process != null) launch.process.onExit().thenRun(() -> beginDrain(launch));
    publish(launch, () -> setTip(Lizzie.resourceBundle.getString("ContributeView.lblTip")));
  }

  private void reader(Launch launch, BufferedReader reader, boolean stderr) {
    Thread thread =
        new Thread(
            () -> {
              String error = null;
              try {
                String line;
                while ((line = reader.readLine()) != null) {
                  synchronized (ContributeEngine.this) {
                    launch.diagnostic.output(stderr ? "stderr" : "stdout", line);
                    if (active == launch && !stopped && !launch.terminal && !launch.permanent())
                      consumeOutput(launch, line, stderr);
                  }
                }
              } catch (Exception failure) {
                error = failure.toString();
              } finally {
                launch.diagnostic.streamEnded(stderr ? "stderr" : "stdout", error);
                synchronized (ContributeEngine.this) {
                  if (stderr) launch.stderrEnded = true;
                  else launch.stdoutEnded = true;
                  // Redirected or closed stderr can reach EOF while contribution continues.
                  // Keep stdout EOF, reader failures and the process onExit callback terminal.
                  if (!stderr || error != null) beginDrain(launch);
                }
              }
            },
            stderr ? "contribution-stderr" : "contribution-stdout");
    thread.setDaemon(true);
    thread.start();
  }

  private void consumeOutput(Launch launch, String line, boolean stderr) {
    JSONObject json = null;
    String trimmed = line.trim();
    if (trimmed.startsWith("{")) {
      try {
        json = new JSONObject(trimmed);
      } catch (JSONException malformed) {
        return;
      }
      if (json.has("error")) {
        launch.error =
            EngineStartupDiagnostic.bounded(
                sanitizer.sanitize(json.optString("error", line)), 4096);
        classify(launch, json.optString("error", line));
      }
    } else {
      classify(launch, line);
      if (!launch.permanent() && STARTING_GAME.matcher(line).matches()) launch.diagnostic.ready();
    }
    if (launch.permanent()) return;
    JSONObject outputJson = json;
    launch.pendingUi++;
    SwingUtilities.invokeLater(
        () -> {
          Runnable notification = null;
          synchronized (ContributeEngine.this) {
            try {
              if (ownsOutput(launch) && !launch.terminal && !launch.permanent())
                notification = consumeUiOutput(launch, line, stderr, outputJson);
            } finally {
              launch.pendingUi--;
            }
          }
          if (notification != null) notification.run();
        });
  }

  private Runnable consumeUiOutput(Launch launch, String line, boolean stderr, JSONObject json) {
    if (json != null) {
      if (json.has("error")) {
        Lizzie.frame.addContributeLine(sanitizer.sanitize(line), !stderr);
        return null;
      }
      if (json.has("gameId")
          && json.has("initialStones")
          && json.has("moves")
          && json.has("move")
          && json.has("moveInfos")
          && getJsonGameInfo(json, contributeGames, unParseGameInfos)) {
        launch.diagnostic.ready();
        setViewGames();
        if (watchingGameIndex == -1 && !contributeGames.isEmpty()) {
          watchingGameIndex = 0;
          setGameToBoard(contributeGames.get(0), watchingGameIndex, false);
          startWatchingGameThread();
        }
      }
      return null;
    }
    if (stderr) {
      Lizzie.frame.addContributeLine(sanitizer.sanitize(line), false);
      return parseTips(line, launch);
    } else return parseLine(line, launch);
  }

  private void classify(Launch launch, String line) {
    if (launch.permanent()) return;
    String key = null;
    if (line.contains("Compile with -DBUILD_DISTRIBUTED")) {
      launch.failure = Failure.DISTRIBUTED_UNSUPPORTED;
      key = "Contribute.tips.useOfficialEngineSupportDistributedTraining";
    } else if (line.contains("This version of KataGo is not enabled for distributed")) {
      launch.failure = Failure.BUILD_REJECTED;
      key = "Contribute.tips.buildRejected";
    } else if (line.contains("not find CA certs")) {
      launch.failure = Failure.CA_CERTIFICATES;
      key = "Contribute.tips.noCAcerts";
    }
    if (key != null) launch.error = Lizzie.resourceBundle.getString(key);
    else if (line.toLowerCase(java.util.Locale.ROOT).contains("error") || line.startsWith("This "))
      launch.error = EngineStartupDiagnostic.bounded(sanitizer.sanitize(line), 4096);
    if (launch.permanent()) {
      launch.diagnostic.fail("startup-output", launch.error);
      beginDrain(launch);
      lifecycle.execute(() -> terminate(launch));
    }
  }

  private synchronized void beginDrain(Launch launch) {
    if (active != launch || stopped || launch.terminal || launch.draining) return;
    launch.draining = true;
    launch.deadline =
        System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(
                EngineStartupDiagnostics.Policy.production().deadlineMillis());
    lifecycle.execute(() -> finishWhenDrained(launch));
  }

  private synchronized void finishWhenDrained(Launch launch) {
    if (active != launch || stopped || launch.terminal) return;
    boolean complete =
        (launch.process == null || !launch.process.isAlive())
            && (launch.stdout == null || launch.stdoutEnded)
            && (launch.stderr == null || launch.stderrEnded);
    // Give already queued game parsing its normal EOF ordering, but never wait indefinitely
    // for UI dispatch. Permanent failures have already been recorded by the readers.
    if ((!complete || (!launch.permanent() && launch.pendingUi > 0))
        && System.nanoTime() < launch.deadline) {
      lifecycle.schedule(() -> finishWhenDrained(launch), 20, TimeUnit.MILLISECONDS);
      return;
    }
    launch.terminal = true;
    String message =
        launch.error.isEmpty()
            ? Lizzie.resourceBundle.getString("Leelaz.engineEndUnormalHint")
            : launch.error;
    boolean normal = isNormalEnd || isSlowQuiting;
    if (!normal) launch.diagnostic.fail("startup-exit", message);
    terminate(launch);
    if (!complete && launch.process != null && launch.process.isAlive())
      launch.process.destroyForcibly();
    if (!normal && !launch.permanent() && launches < 4) {
      if (launch.failure == Failure.CA_CERTIFICATES && !pureCommand && !addCacerts) {
        addCacerts = true;
        engineCommand =
            certificateRecoveryCommand(engineCommand, commands.get(0), useJavaSSH, pureCommand);
      }
      startEngine();
    } else {
      presentWhenCaptured(launch, message, normal);
    }
  }

  static String certificateRecoveryCommand(
      String command, String executable, boolean remote, boolean pure) {
    if (remote || pure) return command;
    return command
        + " -cacerts \""
        + new File(new File(executable).getAbsoluteFile().getParentFile(), "cacert.pem")
        + "\"";
  }

  private synchronized void presentWhenCaptured(Launch launch, String message, boolean normal) {
    if (active != launch || stopped) return;
    if (launch.diagnostic.snapshot() != null && launch.diagnostic.collectingOutput()) {
      lifecycle.schedule(
          () -> presentWhenCaptured(launch, message, normal), 20, TimeUnit.MILLISECONDS);
      return;
    }
    publish(
        launch,
        () -> {
          Lizzie.frame.isContributing = false;
          Menu.engineMenu.setText(Lizzie.resourceBundle.getString("Menu.noEngine"));
          if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.exitedAfterSignal();
          if (!normal) tryToDignostic(message, launch.diagnostic);
        });
    lifecycle.shutdown();
  }

  private synchronized void terminate(Launch launch) {
    if (launch.terminated) return;
    launch.terminated = true;
    launch.diagnostic.beforeTermination();
    AnalysisResourceCoordinator.processStopped(
        this, AnalysisResourceCoordinator.Purpose.OTHER, launch.process);
    if (launch.process != null) launch.process.destroy();
    if (launch.ssh != null) launch.ssh.close();
  }

  private void publish(Launch launch, Runnable action) {
    SwingUtilities.invokeLater(
        () -> {
          synchronized (ContributeEngine.this) {
            if (active == launch
                && !stopped
                && (Lizzie.frame.contributeEngine == null || Lizzie.frame.contributeEngine == this))
              action.run();
          }
        });
  }

  private boolean ownsOutput(Launch launch) {
    return active == launch
        && !stopped
        && (Lizzie.frame.contributeEngine == null || Lizzie.frame.contributeEngine == this);
  }

  private Runnable outputNotification(Launch launch, String message, boolean quitAfterDismissal) {
    return () -> {
      synchronized (ContributeEngine.this) {
        if (!ownsOutput(launch)) return;
      }
      // A modal dialog runs a nested EDT loop. Readers and lifecycle cleanup must stay free,
      // and dismissal must not act on an owner replaced inside that nested loop.
      Utils.showMsg(message);
      if (quitAfterDismissal) {
        synchronized (ContributeEngine.this) {
          if (!ownsOutput(launch) || launch.terminal || launch.permanent()) return;
          normalQuit();
          if (Lizzie.frame.contributeView != null)
            Lizzie.frame.contributeView.setVisible(false);
        }
      }
    };
  }

  private Runnable parseLine(String line, Launch launch) {
    Runnable notification = null;
    // 2022-03-14 09:00:41+0800: KataGo v1.10.0
    if (!getVersion && line.contains("KataGo")) {
      String params[] = line.split(" ");
      if (params.length == 4 && params[3].startsWith("v")) {
        String version[] = params[3].split("\\.");
        if (version.length == 3) {
          try {
            int ver1 = Integer.parseInt(version[0].substring(1));
            int ver2 = Integer.parseInt(version[1]);
            if (ver1 <= 1 && ver2 < 11) {
              notification = outputNotification(
                  launch,
                  Lizzie.resourceBundle.getString("Contribute.version.outOfDate")
                      + " ("
                      + params[3]
                      + "), "
                      + Lizzie.resourceBundle.getString("Contribute.version.outOfDate2"),
                  true);
            }
          } catch (NumberFormatException e) {
            e.printStackTrace();
          }
          getVersion = true;
        }
      }
    }
    Lizzie.frame.addContributeLine(sanitizer.sanitize(line), true);
    if (line.contains("Finis")) {
      // 2021-11-02 09:21:45+0800: Finished game 8 (training), uploaded sgf
      // katago_contribute/kata1/sgfs/kata1-b40c256-s10312780288-d2513725330/155A1E55A4145135.sgf
      // and training data
      // katago_contribute/kata1/tdata/kata1-b40c256-s10312780288-d2513725330/273F621AC4CF6ACF.npz
      // (8 rows)
      String params[] = line.split(" ");
      String sgfPath = "";
      for (int i = 0; i < params.length - 1; i++) {
        if (params[i].equals("sgf")) sgfPath = params[i + 1];
      }
      // katago_contribute/kata1/sgfs/kata1-b40c256-s10312780288-d2513725330/155A1E55A4145135.sgf
      if (sgfPath.length() > 0) {
        String gameId =
            sgfPath.substring(getLastIndexOfFileSep(sgfPath) + 1, sgfPath.lastIndexOf("."));
        if (contributeGames != null) {
          for (ContributeGameInfo game : contributeGames) {
            if (game.gameId.equals(gameId)) {
              game.complete = true;
              if (game == currentWatchGame) maybePlayLastMove(game);
              if (!useJavaSSH) {
                game.gameResult = SGFParser.getResult(courseFile + File.separator + sgfPath);
                if (game == currentWatchGame) setReultToView(game.gameResult);
              }
            }
          }
          setViewGames();
        }
      }
    }
    Runnable tipNotification = parseTips(line, launch);
    return notification == null ? tipNotification : notification;
  }

  private void setViewGames() {
    int finishedGames = 0;
    int playingGames = 0;
    for (ContributeGameInfo game : contributeGames) {
      if (game.complete) finishedGames++;
      else playingGames++;
    }
    if (Lizzie.frame.contributeView != null)
      Lizzie.frame.contributeView.setGames(finishedGames, playingGames);
  }

  private Runnable parseTips(String line, Launch launch) {
    Runnable notification = null;
    if (STARTING_GAME.matcher(line).matches()) {
      launch.diagnostic.ready();
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.startingNewGame"));
    }
    if (line.toLowerCase().contains("predownload")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.predownload")); // ("预先下载权重中...");
    } else if (line.toLowerCase().contains("download")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.download")); // ("正在下载权重...");
    }
    if (line.contains("Invalid username/password")) {
      setTip(
          Lizzie.resourceBundle.getString(
              "Contribute.tips.invalidUserNamePassword")); // ("错误的用户名或密码");
      active.error =
          Lizzie.resourceBundle.getString(
              "Contribute.tips.invalidUserNamePassword"); //// "错误的用户名或密码";
    }
    if (line.contains("No response from server")) {
      setTip(
          Lizzie.resourceBundle.getString("Contribute.tips.noResponseFromServer")); // ("服务器无回应");
      active.error =
          Lizzie.resourceBundle.getString("Contribute.tips.noResponseFromServer"); // //"服务器无回应";
    }
    if (line.contains("Error connecting to server")) {
      setTip(
          Lizzie.resourceBundle.getString(
              "Contribute.tips.errorConnectingToServer")); // ("服务器连接失败");
      active.error =
          Lizzie.resourceBundle.getString("Contribute.tips.errorConnectingToServer"); // "服务器连接失败";
    }
    if (line.contains(" When uploading")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.uploadError")); // ("上传数据出错,请查看控制台");
      active.error =
          Lizzie.resourceBundle.getString("Contribute.tips.uploadError"); // "上传数据出错,请查看控制台";
    }
    if (line.contains("status 200 for initial query")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.initialQuery")); // ("初始化错误,请查看控制台");
      active.error =
          Lizzie.resourceBundle.getString("Contribute.tips.initialQuery"); // "初始化错误,请查看控制台";
    }
    if (line.contains("Could not parse serverUrl")) {
      setTip(
          Lizzie.resourceBundle.getString(
              "Contribute.tips.couldNotParseServerUrl")); // ("无法解析服务器地址");
      active.error =
          Lizzie.resourceBundle.getString("Contribute.tips.couldNotParseServerUrl"); // "无法解析服务器地址";
    }
    if (line.contains("Did you verify your email address")) {
      setTip(
          Lizzie.resourceBundle.getString(
              "Contribute.tips.didYouVerifyYourEmailAddress")); // ("请确认用户是否经过邮箱验证!");
      active.error =
          Lizzie.resourceBundle.getString(
              "Contribute.tips.didYouVerifyYourEmailAddress"); // "请确认用户是否经过邮箱验证!";
    }
    if (line.contains("Model file was incompletely downloaded")) {
      setTip(
          Lizzie.resourceBundle.getString(
              "Contribute.tips.modelFileWasIncompletelyDownloaded")); // ("权重未完全下载");
      active.error =
          Lizzie.resourceBundle.getString(
              "Contribute.tips.modelFileWasIncompletelyDownloaded"); // 权重未完全下载";
    }
    if (line.contains("Signal to stop")) {
      setEternalTip(Lizzie.resourceBundle.getString("Contribute.tips.signalToStop"));
    }
    if (line.contains("Exited cleanly after signal") || line.contains("All cleaned up, quitting")) {
      isNormalEnd = true;
      notification = outputNotification(
          launch, Lizzie.resourceBundle.getString("Contribute.tips.exitedAfterSignal"), false);
      Menu.engineMenu.setText(Lizzie.resourceBundle.getString("Menu.noEngine"));
      if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.exitedAfterSignal();
    }
    if (line.contains("Pausing contribute")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.PauseHaveSent"), true);
    }
    if (line.contains("Resuming contribute")) {
      setTip(Lizzie.resourceBundle.getString("Contribute.tips.ResumeHaveSent"), true);
    }
    return notification;
  }

  private boolean hasEternalTip = false;

  private void setTip(String text) {
    setTip(text, false);
  }

  private void setTip(String text, boolean isPauseAndResume) {
    if (hasEternalTip && !isPauseAndResume) return;
    if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.setTip(text);
  }

  private void setEternalTip(String text) {
    hasEternalTip = true;
    if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.setTip(text);
  }

  private void setReultToView(String result) {
    if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.setResult(result);
  }

  private void setRulesToView(JSONObject rules) {
    if (Lizzie.frame.contributeView != null) {
      Lizzie.frame.contributeView.setRules(rules);
    }
  }

  private void setTypeAndKomiToView(boolean isRatingGame, double komi) {
    if (Lizzie.frame.contributeView != null) {
      Lizzie.frame.contributeView.setKomi(komi);
      Lizzie.frame.contributeView.setType(
          isRatingGame
              ? Lizzie.resourceBundle.getString("contributeView.gameType.ratingGame")
              : Lizzie.resourceBundle.getString("contributeView.gameType.trainingGame"));
    }
  }

  private int getLastIndexOfFileSep(String path) {
    return Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  }

  public void slowQuit() {
    // TODO Auto-generated method stub
    // if (useProcessHandler) {
    if (!isSlowQuiting) {
      try {
        outputStream.write(("quit" + "\n").getBytes());
        outputStream.flush();
        isSlowQuiting = true;
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    } else {
      Utils.showMsg(Lizzie.resourceBundle.getString("Contribute.slowQuit.repeatedly"));
    }
    //  }
  }

  public void togglePause() {
    if (paused) {
      try {
        outputStream.write(("resume" + "\n").getBytes());
        outputStream.flush();
      } catch (IOException e) {
      }
      paused = false;
      Lizzie.frame.isContributing = true;
      Menu.engineMenu.setText(
          Lizzie.resourceBundle.getString("Contribute.engineMenu.contributing"));
    } else {
      try {
        outputStream.write(("pause" + "\n").getBytes());
        outputStream.flush();
      } catch (IOException e) {
      }
      paused = true;
      Lizzie.frame.isContributing = false;
      Menu.engineMenu.setText(Lizzie.resourceBundle.getString("Menu.noEngine"));
    }

    if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.setBtnPauseResume(paused);
  }

  public void pauseAndResume() {
    if (System.currentTimeMillis() - lastPauseResumeTime < 2000) {
      Utils.showMsg(Lizzie.resourceBundle.getString("Contribute.pauseResumeTooFrequently"));
      return;
    }
    lastPauseResumeTime = System.currentTimeMillis();
    togglePause();
  }

  public synchronized void normalQuit() {
    stopped = true;
    isNormalEnd = true;
    if (active != null) terminate(active);
    lifecycle.shutdownNow();
    if (watchGameThread != null) watchGameThread.interrupt();
    Runnable update =
        () -> {
          if (Lizzie.frame.contributeEngine != null && Lizzie.frame.contributeEngine != this)
            return;
          Lizzie.frame.isShowingContributeGame = false;
          Lizzie.frame.isContributing = false;
          Menu.engineMenu.setText(Lizzie.resourceBundle.getString("Menu.noEngine"));
          if (Lizzie.frame.contributeView != null) Lizzie.frame.contributeView.exitedAfterSignal();
          if (Lizzie.frame.contributeEngine == this) Lizzie.frame.contributeEngine = null;
        };
    if (SwingUtilities.isEventDispatchThread()) update.run();
    else SwingUtilities.invokeLater(update);
  }

  private void initializeStreams() {
    inputStream = new BufferedReader(new InputStreamReader(process.getInputStream()));
    outputStream = new BufferedOutputStream(process.getOutputStream());
    errorStream = new BufferedReader(new InputStreamReader(process.getErrorStream()));
  }


  public boolean getJsonGameInfo(
      JSONObject jsonInfo,
      ArrayList<ContributeGameInfo> games,
      ArrayList<ContributeUnParseGameInfo> unParseInfos) {
    if (jsonInfo == null) return false;
    try {
      String gameId = jsonInfo.getString("gameId");
      ContributeGameInfo currentGame = null;
      boolean isExistGame = false;
      if (!games.isEmpty()) {
        for (ContributeGameInfo game : games) {
          if (game.gameId.equals(gameId)) {
            currentGame = game;
            isExistGame = true;
            break;
          }
        }
      }
      if (isExistGame) {
        if (tryToParseJsonGame(currentGame, false, jsonInfo, unParseInfos, false)) {
          tryToUseUnParseGameInfos(currentGame, unParseInfos);
          if (currentGame == currentWatchGame) {
            setGameToBoard(currentGame, watchingGameIndex, false);
            if (Lizzie.config.contributeWatchAlwaysLastMove)
              if (Lizzie.board.getHistory().getCurrentHistoryNode().next().isPresent()
                  && !Lizzie.board
                      .getHistory()
                      .getCurrentHistoryNode()
                      .next()
                      .get()
                      .next()
                      .isPresent()) Lizzie.board.nextMove(true);
          }
        } else return false;
      } else {
        currentGame = new ContributeGameInfo();
        if (!tryToParseJsonGame(currentGame, true, jsonInfo, unParseInfos, false)) return false;
        games.add(currentGame);
      }
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  private void tryToUseUnParseGameInfos(
      ContributeGameInfo currentGame, ArrayList<ContributeUnParseGameInfo> unParseInfos) {
    ContributeUnParseGameInfo unUsedParseInfo = null;
    if (!unParseInfos.isEmpty()) {
      for (ContributeUnParseGameInfo unParseInfo : unParseInfos) {
        if (unParseInfo.gameId.equals(currentGame.gameId)
            && !unParseInfo.used
            && !unParseInfo.skip) {
          unUsedParseInfo = unParseInfo;
          break;
        }
      }
    }
    if (unUsedParseInfo == null) return;
    if (tryToParseJsonGame(currentGame, false, unUsedParseInfo.gameInfo, unParseInfos, true)) {
      unUsedParseInfo.used = true;
      unParseInfos.remove(unUsedParseInfo);
      for (ContributeUnParseGameInfo info : unParseInfos) {
        if (info.gameId.equals(currentGame.gameId)) info.skip = false;
      }
    } else {
      unUsedParseInfo.skip = true;
      tryToUseUnParseGameInfos(currentGame, unParseInfos);
    }
  }

  private boolean tryToParseJsonGame(
      ContributeGameInfo currentGame,
      boolean newGame,
      JSONObject jsonInfo,
      ArrayList<ContributeUnParseGameInfo> unParseInfos,
      boolean fromUnParsedInfo) {
    boolean success = true;
    // 从jsonInfo中解析已有的一局,更新currentGame,如果isWatching,更新到界面上
    if (newGame) {
      currentGame.gameId = jsonInfo.getString("gameId");
      currentGame.blackPlayer = getModelName(jsonInfo.getString("blackPlayer"));
      currentGame.whitePlayer = getModelName(jsonInfo.getString("whitePlayer"));
      currentGame.sizeX = jsonInfo.getInt("boardXSize");
      currentGame.sizeY = jsonInfo.getInt("boardYSize");
      currentGame.rules = jsonInfo.getJSONObject("rules");
      currentGame.komi = currentGame.rules.getDouble("komi");
      currentGame.isRatingGame = !currentGame.blackPlayer.equals(currentGame.whitePlayer);
    }
    JSONArray initStones = jsonInfo.getJSONArray("initialStones");
    if (initStones.length() > 0) {
      ArrayList<ContributeMoveInfo> initStoneList = new ArrayList<ContributeMoveInfo>();
      for (int i = 0; i < initStones.length(); i++) {
        ContributeMoveInfo move = new ContributeMoveInfo();
        List<Object> moveInfo = initStones.getJSONArray(i).toList();
        move.isBlack = moveInfo.get(0).toString().equals("B");
        move.pos = Board.convertNameToCoordinates(moveInfo.get(1).toString(), currentGame.sizeY);
        move.isPass = move.pos[0] < 0;
        initStoneList.add(move);
      }
      currentGame.initMoveList = initStoneList;
    }
    JSONArray historyMoves = jsonInfo.getJSONArray("moves");
    ArrayList<ContributeMoveInfo> historyMoveList = new ArrayList<ContributeMoveInfo>();
    if (historyMoves.length() > 0) {
      for (int i = 0; i < historyMoves.length(); i++) {
        ContributeMoveInfo move = new ContributeMoveInfo();
        List<Object> moveInfo = historyMoves.getJSONArray(i).toList();
        move.isBlack = moveInfo.get(0).toString().equals("B");
        move.pos = Board.convertNameToCoordinates(moveInfo.get(1).toString(), currentGame.sizeY);
        move.isPass = move.pos[0] < 0;
        historyMoveList.add(move);
      }
    }
    if (newGame) {
      currentGame.moveList = historyMoveList;
    }
    if (newGame || (compareMoveList(historyMoveList, currentGame.moveList))) {
      // 成功,获取最后一手和bestmoves
      ContributeMoveInfo move = new ContributeMoveInfo();
      List<Object> lastMove = jsonInfo.getJSONArray("move").toList();
      move.isBlack = lastMove.get(0).toString().equals("B");
      move.pos = Board.convertNameToCoordinates(lastMove.get(1).toString(), currentGame.sizeY);
      move.isPass = move.pos[0] < 0;
      JSONArray moveInfos = jsonInfo.getJSONArray("moveInfos");
      move.candidates = Utils.getBestMovesFromJsonArray(moveInfos, false, move.isBlack);
      if (jsonInfo.has("ownership")) {
        JSONArray ownershipInfos = jsonInfo.getJSONArray("ownership");
        ArrayList<Double> ownershipArray =
            Utils.getOwnershipArrayFromJsonArray(ownershipInfos, move.isBlack);
        if (ownershipArray != null) {
          move.hasOwnership = true;
          move.ownershipArray = ownershipArray;
        } else move.hasOwnership = false;
      }
      currentGame.moveList.add(move);
    } else if (!fromUnParsedInfo) {
      ContributeUnParseGameInfo unParseInfo = new ContributeUnParseGameInfo();
      unParseInfo.gameId = jsonInfo.getString("gameId");
      unParseInfo.gameInfo = jsonInfo;
      unParseInfos.add(unParseInfo);
      success = false;
    }
    return success;
  }

  private String getModelName(String string) {
    // TODO Auto-generated method stub
    String reulst = string;
    try {
      if (string.contains("-")) reulst = string.substring(string.indexOf("-") + 1);
      if (reulst.contains("-d")) reulst = reulst.substring(0, reulst.indexOf("-d"));
    } catch (Exception e) {
      e.printStackTrace();
    }
    return reulst;
  }

  private boolean compareMoveList(
      ArrayList<ContributeMoveInfo> list1, ArrayList<ContributeMoveInfo> list2) {
    if (list1 == null && list2 == null) return true;
    if (list1 == null && list2 != null && list2.isEmpty()) return true;
    if (list1 != null && list1.isEmpty() && list2 == null) return true;
    if (list1 != null && list1.isEmpty() && list2 != null && list2.isEmpty()) return true;
    if (list1 == null && list2 != null || list1 != null && list2 == null) return false;
    if (list1.size() != list2.size()) return false;
    for (int i = 0; i < list1.size(); i++) {
      ContributeMoveInfo move1 = list1.get(i);
      ContributeMoveInfo move2 = list2.get(i);
      if (move1.isBlack != move2.isBlack
          || move1.isPass != move2.isPass
          || move1.pos[0] != move2.pos[0]
          || move1.pos[1] != move2.pos[1]) return false;
    }
    return true;
  }

  public synchronized void setGameToBoard(ContributeGameInfo game, int index, boolean saveGame) {
    ArrayList<ContributeMoveInfo> remainList = new ArrayList<ContributeMoveInfo>();
    if (currentWatchGame == game
        && Board.boardWidth == currentWatchGame.sizeX
        && Board.boardHeight == currentWatchGame.sizeY
        && isContributeGameAndCurrentBoardSame(game, remainList)) {
      if (remainList != null && remainList.size() > 0) {
        setContributeMoveList(remainList, !currentWatchGame.complete);
        if (Lizzie.config.contributeWatchAlwaysLastMove) {
          Lizzie.frame.lastMove();
          Lizzie.frame.renderVarTree(0, 0, true, true);
        } else {
          Lizzie.frame.redrawTree = true;
          Lizzie.frame.refresh();
        }
      }
    } else {
      currentWatchGame = game;
      if (Lizzie.frame.contributeView != null)
        Lizzie.frame.contributeView.setWathGameIndex(watchingGameIndex + 1);
      Lizzie.board.clear(false);
      Lizzie.board.reopen(currentWatchGame.sizeX, currentWatchGame.sizeY);
      Lizzie.board.isKataBoard = true;
      if (game.isRatingGame) {
        Lizzie.board.isPkBoard = true;
        Lizzie.board.isPkBoardKataB = true;
      }
      Lizzie.frame.isShowingContributeGame = true;
      Lizzie.board
          .getHistory()
          .getGameInfo()
          .setPlayerBlack(currentWatchGame.blackPlayer.replaceAll(" ", ""));
      Lizzie.board
          .getHistory()
          .getGameInfo()
          .setPlayerWhite(currentWatchGame.whitePlayer.replaceAll(" ", ""));
      Lizzie.board.getHistory().getGameInfo().setKomi(currentWatchGame.komi);
      Lizzie.board.getHistory().getStart().getData().comment =
          Utils.getRuleString(currentWatchGame.rules)
              + "\r\n"
              + Lizzie.resourceBundle.getString("ContributeView.lblKomi")
              + currentWatchGame.komi;
      setTypeAndKomiToView(currentWatchGame.isRatingGame, currentWatchGame.komi);
      setRulesToView(currentWatchGame.rules);
      if (currentWatchGame.complete) {
        Lizzie.board.getHistory().getGameInfo().setResult(currentWatchGame.gameResult);
        setReultToView(currentWatchGame.gameResult);
      } else setReultToView("");
      if (currentWatchGame.initMoveList != null && currentWatchGame.initMoveList.size() > 0)
        setContributeMoveList(currentWatchGame.initMoveList, false);
      if (currentWatchGame.moveList != null && currentWatchGame.moveList.size() > 0)
        setContributeMoveList(currentWatchGame.moveList, !currentWatchGame.complete);
      boolean moved = false;
      while (Lizzie.board.getHistory().getCurrentHistoryNode().getData().getPlayouts() <= 0) {
        if (Lizzie.board.nextMove(false)) moved = true;
        else break;
      }
      if (moved) {
        Lizzie.frame.redrawTree = true;
        Lizzie.frame.refresh();
        Lizzie.frame.renderVarTree(0, 0, false, false);
        new Thread() {
          public void run() {
            try {
              Thread.sleep(300);
            } catch (InterruptedException e1) {
              // TODO Auto-generated catch block
              e1.printStackTrace();
            }
            Lizzie.frame.renderVarTree(0, 0, true, false);
          }
        }.start();
      }
      if (!saveGame
          && Lizzie.config.contributeAutoSave
          && currentWatchGame.complete
          && !currentWatchGame.saved) {
        saveGame(game, index);
      }
    }
  }

  public void saveAllGames() {
    if (contributeGames == null || contributeGames.isEmpty()) {
      Utils.showMsg(Lizzie.resourceBundle.getString("Contribute.tips.noGameToSave"));
      return;
    }
    int moveNumber = Lizzie.board.getCurrentMovenumber();
    for (int i = 0; i < contributeGames.size(); i++) {
      saveGame(contributeGames.get(i), i);
    }
    setGameToBoard(currentWatchGame, watchingGameIndex, false);
    Lizzie.board.goToMoveNumber(moveNumber);
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    Utils.showMsg(
        Lizzie.resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
            + courseFile
            + File.separator
            + "ContributeGames"
            + File.separator
            + timeStamp);
  }

  private void saveGame(ContributeGameInfo game, int index) {
    if (game != currentWatchGame) {
      setGameToBoard(game, index, true);
    }
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    File autoSaveFile =
        new File(
            courseFile
                + File.separator
                + "ContributeGames"
                + File.separator
                + timeStamp
                + File.separator
                + (index + 1)
                + "_"
                + game.gameId
                + "_"
                + getShortName(Lizzie.board.getHistory().getGameInfo().getPlayerBlack())
                + "_VS_"
                + getShortName(Lizzie.board.getHistory().getGameInfo().getPlayerWhite())
                + "("
                + Lizzie.board.getHistory().getGameInfo().getResult()
                + ").sgf");
    File fileParent = autoSaveFile.getParentFile();
    if (!fileParent.exists()) {
      fileParent.mkdirs();
    }
    try {
      SGFParser.save(Lizzie.board, autoSaveFile.getPath());
      if (game.complete) game.saved = true;
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private String getShortName(String name) {
    if (name.length() > 19) return name.substring(0, 19);
    else return name;
  }

  private void maybePlayLastMove(ContributeGameInfo game) {
    // TODO Auto-generated method stub
    if (game.moveList != null && game.moveList.size() > 0) {
      ContributeMoveInfo move = game.moveList.get(game.moveList.size() - 1);
      BoardHistoryNode lastNode = Lizzie.board.getHistory().getMainEnd();
      if (move.isPass) {
        if (lastNode.getData().lastMoveColor.isBlack() != move.isBlack)
          Lizzie.board
              .getHistory()
              .pass(move.isBlack ? Stone.BLACK : Stone.WHITE, false, false, false, true);
      } else
        Lizzie.board
            .getHistory()
            .place(
                move.pos[0],
                move.pos[1],
                move.isBlack ? Stone.BLACK : Stone.WHITE,
                false,
                false,
                true);
    }
  }

  private void setContributeMoveList(
      ArrayList<ContributeMoveInfo> remainList, boolean holdLastMove) {
    for (int i = 0; i < remainList.size(); i++) {
      ContributeMoveInfo move = remainList.get(i);
      if (move.candidates != null) {
        if (move.hasOwnership && move.ownershipArray != null) {
          Lizzie.board
              .getHistory()
              .getMainEnd()
              .getData()
              .tryToSetBestMoves(
                  move.candidates,
                  Lizzie.board.getHistory().getCurrentTurnPlayerShortName(),
                  false,
                  MoveData.getPlayouts(move.candidates),
                  move.ownershipArray);
        } else
          Lizzie.board
              .getHistory()
              .getMainEnd()
              .getData()
              .tryToSetBestMoves(
                  move.candidates,
                  Lizzie.board.getHistory().getCurrentTurnPlayerShortName(),
                  false,
                  MoveData.getPlayouts(move.candidates));
        if (Lizzie.board.getHistory().getMainEnd().getData().getPlayouts() > 0) {
          Lizzie.board.getHistory().getMainEnd().getData().comment =
              SGFParser.formatCommentPk(Lizzie.board.getHistory().getMainEnd());
        }
      }
      if (i < (holdLastMove ? remainList.size() - 1 : remainList.size())) {
        if (move.isPass)
          Lizzie.board
              .getHistory()
              .pass(move.isBlack ? Stone.BLACK : Stone.WHITE, false, false, false, true);
        else
          Lizzie.board
              .getHistory()
              .place(
                  move.pos[0],
                  move.pos[1],
                  move.isBlack ? Stone.BLACK : Stone.WHITE,
                  false,
                  false,
                  true);
      }
    }
  }

  class sameStatus {
    boolean isSame;
    boolean historyExhausted;
    BoardHistoryNode startNode;
  }

  private boolean isContributeGameAndCurrentBoardSame(
      ContributeGameInfo watchGame, ArrayList<ContributeMoveInfo> remainList) {
    sameStatus status = new sameStatus();
    status.isSame = true;
    status.startNode = skipStaticSnapshotAnchors(Lizzie.board.getHistory().getStart());
    if (watchGame.initMoveList != null && watchGame.initMoveList.size() > 0) {
      status = compareListAndNode(watchGame.initMoveList, status, remainList);
    }
    if (watchGame.moveList != null && watchGame.moveList.size() > 0) {
      status = compareListAndNode(watchGame.moveList, status, remainList);
    }
    return status.isSame;
  }

  private sameStatus compareListAndNode(
      ArrayList<ContributeMoveInfo> list,
      sameStatus status,
      ArrayList<ContributeMoveInfo> remainList) {
    if (status.historyExhausted) {
      getRemainList(list, remainList, 0);
      return status;
    }
    for (int i = 0; i < list.size(); i++) {
      ContributeMoveInfo move = list.get(i);
      status.startNode = skipStaticSnapshotAnchors(status.startNode);
      BoardData nodeData = status.startNode.getData();
      if (!isReplayableHistoryAction(nodeData)) {
        getRemainList(list, remainList, i);
        return status;
      }
      if (nodeMatchesMove(nodeData, move)) {
        Optional<BoardHistoryNode> nextNode = nextHistoryActionNode(status.startNode);
        if (nextNode.isPresent()) {
          status.startNode = nextNode.get();
        } else {
          status.historyExhausted = true;
          getRemainList(list, remainList, i + 1);
          return status;
        }
        continue;
      }
      status.isSame = false;
      return status;
    }
    return status;
  }

  private boolean nodeMatchesMove(BoardData nodeData, ContributeMoveInfo move) {
    if (!isReplayableHistoryAction(nodeData)) {
      return false;
    }
    if (nodeData.isMoveNode() && nodeData.lastMove.isPresent() && !move.isPass) {
      return nodeData.lastMoveColor.isBlack() == move.isBlack
          && nodeData.lastMove.get()[0] == move.pos[0]
          && nodeData.lastMove.get()[1] == move.pos[1];
    }
    return nodeData.isPassNode() && move.isPass && nodeData.lastMoveColor.isBlack() == move.isBlack;
  }

  private BoardHistoryNode skipStaticSnapshotAnchors(BoardHistoryNode startNode) {
    BoardHistoryNode node = startNode;
    while (node.next().isPresent() && !isReplayableHistoryAction(node.getData())) {
      node = node.next().get();
    }
    return node;
  }

  private Optional<BoardHistoryNode> nextHistoryActionNode(BoardHistoryNode startNode) {
    Optional<BoardHistoryNode> nextNode = startNode.next();
    while (nextNode.isPresent() && !isReplayableHistoryAction(nextNode.get().getData())) {
      nextNode = nextNode.get().next();
    }
    return nextNode;
  }

  private boolean isReplayableHistoryAction(BoardData nodeData) {
    return nodeData.isMoveNode() || (nodeData.isPassNode() && !nodeData.dummy);
  }

  private void getRemainList(
      ArrayList<ContributeMoveInfo> list, ArrayList<ContributeMoveInfo> remainList, int i) {
    for (; i < list.size(); i++) {
      remainList.add(list.get(i));
    }
  }

  public void tryToDignostic(String message) {
    tryToDignostic(message, this.currentAttempt);
  }

  public void tryToDignostic(String message, EngineStartupDiagnostics.Attempt attempt) {
    if (active == null || attempt != currentAttempt || stopped) return;
    Launch launch = active;
    if (!launch.terminal) {
      launch.error = message;
      attempt.fail("process-create", message);
      beginDrain(launch);
      return;
    }
    if (!SwingUtilities.isEventDispatchThread()) {
      publish(launch, () -> tryToDignostic(message, attempt));
      return;
    }
    if (launch.presented) return;
    launch.presented = true;
    if (attempt.snapshot() == null) attempt.fail("startup-exit", message);
    EngineFailedMessage.showDialog(
        commands != null ? commands : Utils.splitCommand(engineCommand),
        sanitizer.sanitize(engineCommand),
        sanitizer.sanitize(message),
        !useJavaSSH && OS.isWindows(),
        false,
        true,
        true,
        null,
        dialog -> {
          if (attempt != null && attempt == this.currentAttempt) {
            dialog.bindStartupDiagnostic(attempt);
          }
        });
  }

  private void startWatchingGameThread() {
    Runnable runnable =
        new Runnable() {
          public void run() {
            int timeCount = 0;
            BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
            while (true) {
              try {
                Thread.sleep(50);
                timeCount++;
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                break;
              }
              if (changeWatchingGameIndex >= 0) {
                if (changeWatchingGameIndex > contributeGames.size() - 1)
                  changeWatchingGameIndex = contributeGames.size() - 1;
                if (changeWatchingGameIndex != watchingGameIndex) {
                  watchingGameIndex = changeWatchingGameIndex;
                  changeWatchingGameIndex = -1;
                  changeGame(watchingGameIndex);
                  node = Lizzie.board.getHistory().getCurrentHistoryNode();
                  timeCount = 0;
                }
              }
              if (Lizzie.config.contributeWatchAutoPlay) {
                if (timeCount * 50.0 / 1000.0 >= Lizzie.config.contributeWatchAutoPlayInterval) {
                  if (node == Lizzie.board.getHistory().getCurrentHistoryNode()) {
                    if (!Lizzie.board.nextMove(true)) {
                      boolean shouldGoToNextGame = Lizzie.config.contributeWatchAutoPlayNextGame;
                      if (shouldGoToNextGame && currentWatchGame.complete) {
                        boolean needChange = false;
                        while (shouldGoToNextGame
                            && contributeGames.size() > watchingGameIndex + 1) {
                          watchingGameIndex++;
                          needChange = true;
                          shouldGoToNextGame = false;
                          if (Lizzie.config.contributeWatchSkipNone19) {
                            if (contributeGames.get(watchingGameIndex).sizeX != 19
                                || contributeGames.get(watchingGameIndex).sizeY != 19)
                              shouldGoToNextGame = true;
                          }
                        }
                        if (needChange) {
                          changeGame(watchingGameIndex);
                          node = Lizzie.board.getHistory().getCurrentHistoryNode();
                          timeCount = 0;
                        }
                      }
                    }
                  }
                  node = Lizzie.board.getHistory().getCurrentHistoryNode();
                  timeCount = 0;
                }
              }
            }
          }

          private void changeGame(int index) {
            // TODO Auto-generated method stub
            setGameToBoard(contributeGames.get(watchingGameIndex), watchingGameIndex, false);
            if (Lizzie.config.contributeWatchAlwaysLastMove) Lizzie.frame.lastMove();
          }
        };
    watchGameThread = new Thread(runnable);
    watchGameThread.start();
  }

  public void setWatchGame(int index) {
    // TODO Auto-generated method stub
    changeWatchingGameIndex = index;
  }

  public int getGameMaxIndex() {
    // TODO Auto-generated method stub
    if (contributeGames == null || contributeGames.size() == 0) return 0;
    return contributeGames.size() - 1;
  }

  //  public boolean canSlowClose() {
  //    return useProcessHandler;
  //  }
}
