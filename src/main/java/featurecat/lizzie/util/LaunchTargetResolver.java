package featurecat.lizzie.util;

import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the program that receives a command's arguments by following launch grammars.
 *
 * <p>Names are never searched for anywhere in the text: a real shell can split words that a
 * simple tokenizer keeps together, and a launcher option such as {@code --cd /home/ssh} is data,
 * not a program. Each argument is therefore interpreted at its grammatical position, and any
 * construct not modeled here makes the result {@link Target#UNDETERMINED}. Callers that persist a
 * command must accept only {@link Target#LOCAL}.
 *
 * <p>The model covers the command line only. What a program, script, PATH entry or shell startup
 * file does after it starts is outside it, as it is for every local engine.
 */
public final class LaunchTargetResolver {
  /** Ordered by severity, so combining interpretations keeps the most restrictive one. */
  public enum Target {
    LOCAL,
    UNDETERMINED,
    REMOTE
  }

  private static final int MAX_DEPTH = 8;
  private static final Set<String> REMOTE_SHELL_CLIENTS =
      Set.of("ssh", "slogin", "plink", "autossh", "mosh", "dbclient", "sshpass", "rsh", "rlogin");
  private static final Set<String> POSIX_SHELLS =
      Set.of(
          "sh", "bash", "dash", "ash", "zsh", "ksh", "mksh", "oksh", "yash", "fish", "csh",
          "tcsh");
  // Programs whose contract is to run a command named in their arguments, with grammars that are
  // not modeled. None of them is a GTP engine, so rejecting them costs no local diagnostics.
  private static final Set<String> UNMODELED_FORWARDERS =
      Set.of(
          // Windows launchers, terminals and WSL hosts.
          "wslhost", "wslg", "wt", "conhost", "runas", "psexec", "psexec64", "winpty", "mintty",
          "cygstart", "start", "call", "powershell", "pwsh", "wine", "wine64", "busybox",
          // Containers, sandboxes, remote and package runners.
          "docker", "podman", "nerdctl", "kubectl", "oc", "lxc", "flatpak", "snap", "firejail",
          "bwrap", "proot", "chroot", "nsenter", "unshare", "systemd-run", "toolbox", "distrobox",
          "gcloud", "az", "aws", "nix", "nix-shell", "conda", "mamba", "micromamba", "pixi",
          "poetry", "pipenv", "pipx", "uv", "uvx", "npx", "bunx", "pnpm", "yarn", "npm",
          // Privilege, session and process wrappers.
          "sudo", "doas", "su", "runuser", "pkexec", "sg", "gosu", "su-exec", "setpriv",
          "timeout", "nice", "ionice", "stdbuf", "setsid", "taskset", "chrt", "numactl",
          "cpulimit", "time", "xargs", "watch", "script", "unbuffer", "expect", "rlwrap", "tmux",
          "screen", "dtach", "flock", "caffeinate", "faketime", "strace", "ltrace", "gdb",
          "valgrind", "torsocks", "proxychains", "proxychains4", "tsocks", "ssh-agent", "arch",
          "builtin", "eval");
  // WSL distribution launchers such as ubuntu2204.exe accept "run <command>".
  private static final List<String> WSL_DISTRIBUTION_LAUNCHERS =
      List.of(
          "ubuntu", "debian", "kali", "opensuse", "sles", "suse", "alpine", "fedoraremix",
          "oraclelinux", "almalinux", "rockylinux", "archlinux");
  // Shell words that do not name the program to run: keywords, precommand modifiers and
  // builtins that evaluate or schedule other words as commands.
  private static final Set<String> SHELL_RESERVED_WORDS =
      Set.of(
          "-", ".", "and", "begin", "builtin", "case", "coproc", "do", "done", "elif", "else",
          "emulate", "end", "esac", "eval", "fc", "fi", "for", "foreach", "function", "if", "in",
          "nocorrect", "noglob", "not", "or", "repeat", "sched", "select", "source", "switch",
          "then", "time", "trap", "until", "while");
  // Option letters with the same no-value meaning in the POSIX shell family. Others either take
  // a value in some shell (o, O, C, d, f, p) or read commands from elsewhere (s).
  private static final String SHELL_FLAGS = "eilnuvx";
  private static final Set<String> SHELL_LONG_FLAGS =
      Set.of("--login", "--noprofile", "--norc", "--posix", "--restricted", "--verbose");
  private static final String PYTHON_FLAGS = "bBdEhiIOPqRsSuvVx";
  private static final Pattern PY_LAUNCHER_VERSION =
      Pattern.compile("-(?:V:\\S+|\\d+(?:\\.\\d+)*(?:-(?:32|64|arm64))?)");
  private static final Pattern SHELL_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
  private static final Set<String> CMD_SWITCHES =
      Set.of(
          "/a", "/u", "/q", "/d", "/s", "/x", "/y", "/e:on", "/e:off", "/f:on", "/f:off",
          "/v:on", "/v:off");
  private static final Pattern CMD_COLOR_SWITCH = Pattern.compile("/t:[0-9a-f]{1,2}");
  private static final Set<String> CMD_INTERNAL_COMMANDS =
      Set.of(
          "assoc", "break", "call", "cd", "chdir", "cls", "color", "copy", "date", "del", "dir",
          "dpath", "echo", "endlocal", "erase", "exit", "for", "ftype", "goto", "if", "keys",
          "md", "mkdir", "mklink", "move", "path", "pause", "popd", "prompt", "pushd", "rd",
          "rem", "ren", "rename", "rmdir", "set", "setlocal", "shift", "start", "time", "title",
          "type", "ver", "verify", "vol");
  private static final String CMD_METACHARACTERS = "\"&|<>^%!()";
  private static final List<String> WINDOWS_EXECUTABLE_EXTENSIONS =
      List.of(".exe", ".com", ".bat", ".cmd");
  private static final List<String> SCRIPT_EXTENSIONS =
      List.of(".ps1", ".sh", ".py", ".pyw", ".js", ".mjs", ".vbs", ".wsf", ".rb", ".pl");

  private LaunchTargetResolver() {}

  /**
   * Resolves an argument vector started by {@link ProcessBuilder} on Windows.
   *
   * <p>CreateProcess extends an unquoted program name across spaces until a file exists, so a
   * path split by missing quotes can still start the program at its end. Every reachable
   * candidate is resolved and the most restrictive result wins.
   */
  public static Target resolveWindowsLaunch(List<String> argv) {
    if (argv == null || argv.isEmpty()) return Target.UNDETERMINED;
    Target result = resolve(argv.get(0), rest(argv, 1), 0);
    String candidate = argv.get(0);
    // ProcessBuilder quotes a name containing whitespace, which disables the search.
    if (candidate == null || hasWhitespace(candidate)) return result;
    for (int end = 1; end < argv.size(); end++) {
      String next = argv.get(end);
      if (hasExecutableExtension(candidate) || !continuesUnquotedName(next)) break;
      candidate = candidate + " " + next;
      if (!isPlausibleWindowsPath(candidate)) break;
      result = moreRestrictive(result, resolve(candidate, rest(argv, end + 1), 0));
    }
    return result;
  }

  /** Whether cmd.exe runs this first word itself instead of starting a program. */
  public static boolean isCmdInternalCommand(String word) {
    if (word == null) return false;
    String lower = word.toLowerCase(Locale.ROOT);
    int end = 0;
    while (end < lower.length() && lower.charAt(end) >= 'a' && lower.charAt(end) <= 'z') end++;
    if (!CMD_INTERNAL_COMMANDS.contains(lower.substring(0, end))) return false;
    if (end == lower.length()) return true;
    char next = lower.charAt(end);
    // cmd.exe ends an internal command name at punctuation, as in "echo." or "cd..".
    return !Character.isLetterOrDigit(next) && next != '-' && next != '_';
  }

  private static Target resolve(String program, List<String> arguments, int depth) {
    if (depth > MAX_DEPTH) return Target.UNDETERMINED;
    if (program != null && RemoteComputeConfig.isRemoteComputeEngineCommand(program)) {
      return Target.REMOTE;
    }
    String file = fileName(program);
    if (file == null) return Target.UNDETERMINED;
    if (isRemoteShellClient(file)) return Target.REMOTE;
    String name = withoutExtension(file, List.of(".exe", ".com"));
    if (name.equals("wsl")) return resolveWsl(arguments, depth);
    if (POSIX_SHELLS.contains(name)) return resolveShell(arguments, depth);
    if (name.equals("cmd")) return resolveCmd(arguments, depth);
    if (name.equals("env")) return resolveEnv(arguments, depth);
    if (name.equals("nohup")) return resolveNohup(arguments, depth);
    if (name.equals("exec")) return resolveExec(arguments, depth);
    if (name.equals("command")) return resolveCommandBuiltin(arguments, depth);
    if (isPython(name)) return resolvePython(arguments);
    if (CommandLaunchHelper.hasNumericVersionSuffix(name, "java")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "javaw")) {
      return Target.LOCAL; // Java runs a class or archive and has no inline-code option.
    }
    if (isScriptInterpreter(name)) return resolveScriptOperand(name, arguments);
    if (file.endsWith(".bat") || file.endsWith(".cmd")) {
      // CreateProcess runs batch files through cmd.exe, which parses their arguments again.
      for (String argument : arguments) {
        if (argument == null || !isQuotableCmdWord(argument)) return Target.UNDETERMINED;
      }
      return Target.LOCAL;
    }
    if (SCRIPT_EXTENSIONS.stream().anyMatch(file::endsWith)) return Target.LOCAL;
    if (isUnmodeledForwarder(name) || CommandLaunchHelper.isIndirectLauncher(program)) {
      return Target.UNDETERMINED;
    }
    return Target.LOCAL;
  }

  private static Target resolveArguments(List<String> argv, int depth) {
    return argv.isEmpty() ? Target.UNDETERMINED : resolve(argv.get(0), rest(argv, 1), depth + 1);
  }

  /** A script or module operand is the program; only its name can identify a remote client. */
  private static Target terminal(String operand) {
    String file = fileName(operand);
    if (file == null) return Target.UNDETERMINED;
    return isRemoteShellClient(file) ? Target.REMOTE : Target.LOCAL;
  }

  private static Target resolveWsl(List<String> arguments, int depth) {
    boolean throughShell = true;
    for (int index = 0; index < arguments.size(); ) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      switch (argument) {
        case "-d", "--distribution", "--distribution-id", "-u", "--user", "--cd" -> {
          if (index + 1 >= arguments.size() || arguments.get(index + 1) == null) {
            return Target.UNDETERMINED;
          }
          index += 2;
        }
        case "--shell-type" -> {
          String type = index + 1 < arguments.size() ? arguments.get(index + 1) : null;
          if ("none".equals(type)) {
            throughShell = false;
          } else if (!"standard".equals(type) && !"login".equals(type)) {
            return Target.UNDETERMINED;
          }
          index += 2;
        }
        case "--system", "~" -> index++;
        case "-e", "--exec" -> {
          return resolveArguments(rest(arguments, index + 1), depth);
        }
        case "--" -> {
          return resolveWslCommand(rest(arguments, index + 1), throughShell, depth);
        }
        default -> {
          // Management commands and unknown options do not start a command we can name.
          if (argument.startsWith("-")) return Target.UNDETERMINED;
          return resolveWslCommand(rest(arguments, index), throughShell, depth);
        }
      }
    }
    return Target.UNDETERMINED; // Without a command, WSL starts an interactive shell.
  }

  private static Target resolveWslCommand(List<String> command, boolean throughShell, int depth) {
    if (command.isEmpty()) return Target.UNDETERMINED;
    // The default shell re-parses the remaining arguments as one command line.
    return throughShell ? resolveShellWords(command, depth) : resolveArguments(command, depth);
  }

  private static Target resolveShell(List<String> arguments, int depth) {
    int index = 0;
    while (index < arguments.size()) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      if (argument.equals("--") || argument.equals("-")) {
        index++;
        break;
      }
      if (argument.startsWith("--")) {
        if (!SHELL_LONG_FLAGS.contains(argument)) return Target.UNDETERMINED;
        index++;
        continue;
      }
      if (argument.length() < 2 || (argument.charAt(0) != '-' && argument.charAt(0) != '+')) {
        break;
      }
      String letters = argument.substring(1);
      boolean commandText = argument.charAt(0) == '-' && letters.endsWith("c");
      String flags = commandText ? letters.substring(0, letters.length() - 1) : letters;
      for (int k = 0; k < flags.length(); k++) {
        if (SHELL_FLAGS.indexOf(flags.charAt(k)) < 0) return Target.UNDETERMINED;
      }
      index++;
      if (commandText) {
        // Shells differ on options after -c; require the command text to follow it directly.
        return index < arguments.size()
            ? resolveShellText(arguments.get(index), depth)
            : Target.UNDETERMINED;
      }
    }
    if (index >= arguments.size()) return Target.UNDETERMINED; // Commands from standard input.
    String script = arguments.get(index);
    // Windows bash.exe treats a leading "~" as a directory, not as the script.
    return "~".equals(script) ? Target.UNDETERMINED : terminal(script);
  }

  private static Target resolveShellText(String text, int depth) {
    if (text == null || text.startsWith("-") || text.startsWith("+")) return Target.UNDETERMINED;
    List<String> words = new ArrayList<>();
    for (String word : text.split(" ", -1)) {
      if (!word.isEmpty()) words.add(word);
    }
    return resolveShellWords(words, depth);
  }

  /**
   * Resolves words that a shell parses. Only plain words are accepted, so quoting, expansion,
   * globbing, operators and whitespace other than a space cannot make the shell split or join
   * words differently from this model.
   */
  private static Target resolveShellWords(List<String> words, int depth) {
    for (String word : words) {
      if (!isPlainShellWord(word)) return Target.UNDETERMINED;
    }
    int index = 0;
    while (index < words.size() && SHELL_ASSIGNMENT.matcher(words.get(index)).matches()) {
      index++;
    }
    if (index == words.size()) return Target.UNDETERMINED;
    String program = words.get(index);
    // zsh expands a leading "=" to the path of the named command.
    if (program.startsWith("=") || SHELL_RESERVED_WORDS.contains(program)) {
      return Target.UNDETERMINED;
    }
    return resolve(program, rest(words, index + 1), depth + 1);
  }

  private static Target resolveCmd(List<String> arguments, int depth) {
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      String option = argument.toLowerCase(Locale.ROOT);
      if (option.equals("/c") || option.equals("/k")) {
        List<String> words = rest(arguments, index + 1);
        if (words.isEmpty()) return Target.UNDETERMINED;
        for (String word : words) {
          if (!isPlainCmdWord(word)) return Target.UNDETERMINED;
        }
        String program = words.get(0);
        // cmd.exe also ends the first word at these delimiters and runs internal commands itself.
        if (isCmdInternalCommand(program) || containsAny(program, ",;=/@")) {
          return Target.UNDETERMINED;
        }
        return resolve(program, rest(words, 1), depth + 1);
      }
      if (!CMD_SWITCHES.contains(option) && !CMD_COLOR_SWITCH.matcher(option).matches()) {
        return Target.UNDETERMINED;
      }
    }
    return Target.UNDETERMINED; // An interactive cmd.exe reads commands from standard input.
  }

  private static Target resolveEnv(List<String> arguments, int depth) {
    int index = 0;
    while (index < arguments.size()) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      if (argument.equals("--")) {
        index++;
        break;
      }
      if (Set.of("-", "-i", "--ignore-environment", "-0", "--null", "-v", "--debug")
          .contains(argument)) {
        index++;
      } else if (Set.of("-u", "--unset", "-C", "--chdir").contains(argument)) {
        index += 2;
      } else if (argument.startsWith("--unset=")
          || argument.startsWith("--chdir=")
          || (argument.length() > 2 && (argument.startsWith("-u") || argument.startsWith("-C")))) {
        index++;
      } else if (argument.startsWith("-")) {
        return Target.UNDETERMINED; // Includes -S, which splits a string into a new command.
      } else {
        break;
      }
    }
    while (index < arguments.size()
        && arguments.get(index) != null
        && SHELL_ASSIGNMENT.matcher(arguments.get(index)).matches()) {
      index++;
    }
    return resolveArguments(rest(arguments, index), depth);
  }

  private static Target resolveNohup(List<String> arguments, int depth) {
    if (arguments.isEmpty() || arguments.get(0) == null) return Target.UNDETERMINED;
    if (arguments.get(0).equals("--")) return resolveArguments(rest(arguments, 1), depth);
    if (arguments.get(0).startsWith("-")) return Target.UNDETERMINED;
    return resolveArguments(arguments, depth);
  }

  private static Target resolveExec(List<String> arguments, int depth) {
    int index = 0;
    while (index < arguments.size()) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      if (argument.equals("--")) {
        index++;
        break;
      }
      if (argument.equals("-a")) {
        index += 2;
      } else if (argument.matches("-[cl]+")) {
        index++;
      } else if (argument.startsWith("-")) {
        return Target.UNDETERMINED;
      } else {
        break;
      }
    }
    return resolveArguments(rest(arguments, index), depth);
  }

  private static Target resolveCommandBuiltin(List<String> arguments, int depth) {
    int index = 0;
    while (index < arguments.size()) {
      String argument = arguments.get(index);
      if (argument == null) return Target.UNDETERMINED;
      if (argument.equals("--")) {
        index++;
        break;
      }
      if (!argument.equals("-p")) {
        if (argument.startsWith("-")) return Target.UNDETERMINED; // -v and -V do not run it.
        break;
      }
      index++;
    }
    return resolveArguments(rest(arguments, index), depth);
  }

  private static Target resolvePython(List<String> arguments) {
    for (int index = 0; index < arguments.size(); index++) {
      String argument = arguments.get(index);
      if (argument == null || argument.equals("-")) return Target.UNDETERMINED;
      if (argument.equals("--")) {
        return index + 1 < arguments.size()
            ? terminal(arguments.get(index + 1))
            : Target.UNDETERMINED;
      }
      if (!argument.startsWith("-")) return terminal(argument);
      if (argument.startsWith("--")) return Target.UNDETERMINED;
      if (PY_LAUNCHER_VERSION.matcher(argument).matches()) continue;
      for (int k = 1; k < argument.length(); k++) {
        char option = argument.charAt(k);
        if (option == 'm') {
          boolean hasModule = k + 1 < argument.length() || index + 1 < arguments.size();
          return hasModule ? Target.LOCAL : Target.UNDETERMINED;
        }
        if (option == 'W' || option == 'X') {
          if (k + 1 == argument.length()) index++;
          break;
        }
        // Rejects -c, whose value is program text rather than a file.
        if (PYTHON_FLAGS.indexOf(option) < 0) return Target.UNDETERMINED;
      }
    }
    return Target.UNDETERMINED; // Without a script, Python reads the program from standard input.
  }

  /** Interpreters whose option grammars are not modeled: the script must come first. */
  private static Target resolveScriptOperand(String interpreter, List<String> arguments) {
    if (arguments.isEmpty()) return Target.UNDETERMINED;
    String operand = arguments.get(0);
    if (operand == null || operand.isEmpty() || operand.startsWith("-") || operand.startsWith("//")) {
      return Target.UNDETERMINED;
    }
    if ((interpreter.equals("deno") || interpreter.equals("bun"))
        && Set.of("eval", "x", "repl").contains(operand)) {
      return Target.UNDETERMINED;
    }
    return terminal(operand);
  }

  private static boolean isPython(String name) {
    return name.equals("py")
        || name.equals("pyw")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "python")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "pythonw")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "pypy");
  }

  private static boolean isScriptInterpreter(String name) {
    return Set.of("nodejs", "bun", "deno", "cscript", "wscript", "dotnet", "mono").contains(name)
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "node")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "ruby")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "perl")
        || CommandLaunchHelper.hasNumericVersionSuffix(name, "php");
  }

  private static boolean isUnmodeledForwarder(String name) {
    if (UNMODELED_FORWARDERS.contains(name)) return true;
    for (String launcher : WSL_DISTRIBUTION_LAUNCHERS) {
      if (name.equals(launcher)
          || (name.startsWith(launcher)
              && "0123456789-_.".indexOf(name.charAt(launcher.length())) >= 0)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRemoteShellClient(String file) {
    return REMOTE_SHELL_CLIENTS.contains(withoutExtension(file, WINDOWS_EXECUTABLE_EXTENSIONS));
  }

  /**
   * The lower-case file name Windows opens for this token, or null when the token cannot name a
   * file. Windows ignores trailing dots and spaces, and a colon after the name selects a stream.
   */
  private static String fileName(String token) {
    if (token == null || token.isBlank() || token.contains("://") || hasControl(token)) {
      return null;
    }
    String name = token.substring(Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\')) + 1);
    if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
      name = name.substring(2); // Drive-relative path such as C:ssh.exe.
    }
    int end = name.length();
    while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) end--;
    name = name.substring(0, end);
    if (name.isEmpty() || name.indexOf(':') >= 0) return null;
    return name.toLowerCase(Locale.ROOT);
  }

  private static String withoutExtension(String file, List<String> extensions) {
    for (String extension : extensions) {
      if (file.endsWith(extension) && file.length() > extension.length()) {
        return file.substring(0, file.length() - extension.length());
      }
    }
    return file;
  }

  private static boolean hasExecutableExtension(String candidate) {
    String file = fileName(candidate);
    return file == null || WINDOWS_EXECUTABLE_EXTENSIONS.stream().anyMatch(file::endsWith);
  }

  private static boolean continuesUnquotedName(String next) {
    if (next == null
        || next.isEmpty()
        || hasWhitespace(next)
        || next.indexOf('"') >= 0
        || next.startsWith("/")) { // A switch or Unix path, never the rest of a Windows path.
      return false;
    }
    // An option ends the name unless it is itself a path fragment such as "-Tools\ssh.exe".
    return !next.startsWith("-") || next.indexOf('/') >= 0 || next.indexOf('\\') >= 0;
  }

  private static boolean isPlausibleWindowsPath(String path) {
    for (int i = 0; i < path.length(); i++) {
      char value = path.charAt(i);
      if ("<>\"|?*".indexOf(value) >= 0 || Character.isISOControl(value)) return false;
      if (value == ':' && (i != 1 || !Character.isLetter(path.charAt(0)))) return false;
    }
    return true;
  }

  private static boolean isPlainShellWord(String word) {
    if (word == null || word.isEmpty()) return false;
    for (int i = 0; i < word.length(); ) {
      int value = word.codePointAt(i);
      boolean plain =
          Character.isLetterOrDigit(value)
              || "_-./:,+=@".indexOf(value) >= 0
              || (value == '~' && i == 0); // Tilde expansion changes only the directory prefix.
      if (!plain) return false;
      i += Character.charCount(value);
    }
    return true;
  }

  private static boolean isPlainCmdWord(String word) {
    return word != null
        && !word.isEmpty()
        && !hasWhitespace(word)
        && !hasControl(word)
        && !containsAny(word, CMD_METACHARACTERS);
  }

  /** A word ProcessBuilder quotes, so cmd.exe keeps its spaces without reading operators. */
  private static boolean isQuotableCmdWord(String word) {
    return !word.isEmpty() && !hasControl(word) && !containsAny(word, CMD_METACHARACTERS);
  }

  private static boolean hasWhitespace(String text) {
    return text.chars().anyMatch(Character::isWhitespace);
  }

  private static boolean hasControl(String text) {
    return text.chars().anyMatch(Character::isISOControl);
  }

  private static boolean containsAny(String text, String characters) {
    for (int i = 0; i < text.length(); i++) {
      if (characters.indexOf(text.charAt(i)) >= 0) return true;
    }
    return false;
  }

  private static Target moreRestrictive(Target left, Target right) {
    return left.ordinal() >= right.ordinal() ? left : right;
  }

  private static <T> List<T> rest(List<T> values, int from) {
    return from >= values.size() ? List.of() : values.subList(from, values.size());
  }
}
